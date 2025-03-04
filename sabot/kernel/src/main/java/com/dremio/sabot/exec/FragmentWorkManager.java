/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot.exec;

import java.sql.Timestamp;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Provider;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.curator.utils.CloseableExecutorService;

import com.dremio.common.AutoCloseables;
import com.dremio.common.concurrent.ExtendedLatch;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.proto.CoordExecRPC.FragmentStatus;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos;
import com.dremio.exec.proto.UserBitShared.MinorFragmentProfile;
import com.dremio.exec.proto.UserBitShared.OperatorProfile;
import com.dremio.exec.proto.UserBitShared.StreamProfile;
import com.dremio.exec.server.BootStrapContext;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.work.SafeExit;
import com.dremio.exec.work.WorkStats;
import com.dremio.metrics.Metrics;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.ContextInformationFactory;
import com.dremio.sabot.exec.fragment.FragmentExecutor;
import com.dremio.sabot.exec.fragment.FragmentExecutorBuilder;
import com.dremio.sabot.exec.rpc.CoordToExecHandlerImpl;
import com.dremio.sabot.exec.rpc.ExecProtocol;
import com.dremio.sabot.exec.rpc.ExecTunnel;
import com.dremio.sabot.rpc.CoordToExecHandler;
import com.dremio.sabot.rpc.Protocols;
import com.dremio.sabot.task.TaskPool;
import com.dremio.service.BindingCreator;
import com.dremio.service.Service;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.users.SystemUser;
import com.dremio.services.fabric.api.FabricRunnerFactory;
import com.dremio.services.fabric.api.FabricService;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;

/**
 * Service managing fragment execution.
 */
public class FragmentWorkManager implements Service, SafeExit {
//  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FragmentWorkManager.class);

  private final BootStrapContext context;
  private final Provider<NodeEndpoint> identity;
  private final Provider<SabotContext> dbContext;
  private final BindingCreator bindingCreator;
  private final Provider<FabricService> fabricServiceProvider;
  private final Provider<CatalogService> sources;
  private final Provider<ContextInformationFactory> contextInformationFactory;
  private final Provider<WorkloadTicketDepot> workloadTicketDepotProvider;

  private FragmentStatusThread statusThread;
  private ThreadsStatsCollector statsCollectorThread;
  private HeapMonitorThread heapMonitorThread;

  private final Provider<TaskPool> pool;
  private FragmentExecutors fragmentExecutors;
  private SabotContext bitContext;
  private BufferAllocator allocator;
  private WorkloadTicketDepot ticketDepot;
  private QueriesClerk clerk;
  private ExecutorService executor;
  private CloseableExecutorService closeableExecutor;

  private ExtendedLatch exitLatch = null; // This is used to wait to exit when things are still running


  public FragmentWorkManager(
      final BootStrapContext context,
      Provider<NodeEndpoint> identity,
      final Provider<SabotContext> dbContext,
      final Provider<FabricService> fabricServiceProvider,
      final Provider<CatalogService> sources,
      final Provider<ContextInformationFactory> contextInformationFactory,
      final Provider<WorkloadTicketDepot> workloadTicketDepotProvider,
      final BindingCreator bindingCreator,
      final Provider<TaskPool> taskPool
      ) {
    this.context = context;
    this.identity = identity;
    this.sources = sources;
    this.fabricServiceProvider = fabricServiceProvider;
    this.dbContext = dbContext;
    this.bindingCreator = bindingCreator;
    this.contextInformationFactory = contextInformationFactory;
    this.workloadTicketDepotProvider = workloadTicketDepotProvider;
    this.pool = taskPool;

    bindingCreator.bind(WorkStats.class, new WorkStatsImpl());
  }

  /**
   * Waits until it is safe to exit. Blocks until all currently running fragments have completed.
   *
   * <p>This is intended to be used by {@link com.dremio.exec.server.SabotNode#close()}.</p>
   */
  public void waitToExit() {
    synchronized(this) {
      if (fragmentExecutors == null || fragmentExecutors.size() == 0) {
        return;
      }

      exitLatch = new ExtendedLatch();
    }

    // Wait for at most 5 seconds or until the latch is released.
    exitLatch.awaitUninterruptibly(5000);
  }

  private class WorkStatsImpl implements WorkStats {

    @Override
    public Iterable<TaskPool.ThreadInfo> getSlicingThreads() {
      return pool.get().getSlicingThreads();
    }

    /**
     * @return number of running fragments / max width per node
     */
    @Override
    public float getClusterLoad() {
      final long maxWidthPerNode = bitContext.getClusterResourceInformation().getAverageExecutorCores(bitContext.getOptionManager());
      Preconditions.checkState(maxWidthPerNode > 0, "No executors are available. Unable to determine cluster load");
      return fragmentExecutors.size() / (maxWidthPerNode * 1.0f);
    }

    @Override
    public double getMaxWidthFactor() {
      final OptionManager options = bitContext.getOptionManager();
      final double loadCutoff = options.getOption(ExecConstants.LOAD_CUT_OFF);
      final double loadReduction = options.getOption(ExecConstants.LOAD_REDUCTION);

      float clusterLoad = getClusterLoad();
      if (clusterLoad < loadCutoff) {
        return 1.0; // no reduction when load is below load.cut_off
      }

      return Math.max(0, 1.0 - clusterLoad * loadReduction);
    }

    private class FragmentInfoTransformer implements Function<FragmentExecutor, FragmentInfo>{

      @Override
      public FragmentInfo apply(final FragmentExecutor fragmentExecutor) {
        final FragmentStatus status = fragmentExecutor.getStatus();
        final ExecProtos.FragmentHandle handle = fragmentExecutor.getHandle();
        final MinorFragmentProfile profile = status == null ? null : status.getProfile();
        Long memoryUsed = profile == null ? 0 : profile.getMemoryUsed();
        Long rowsProcessed = profile == null ? 0 : getRowsProcessed(profile);
        Timestamp startTime = profile == null ? new Timestamp(0) : new Timestamp(profile.getStartTime());
        return new FragmentInfo(dbContext.get().getEndpoint().getAddress(),
          QueryIdHelper.getQueryId(handle.getQueryId()),
          handle.getMajorFragmentId(),
          handle.getMinorFragmentId(),
          memoryUsed,
          rowsProcessed,
          startTime,
          fragmentExecutor.getBlockingStatus(),
          fragmentExecutor.getTaskDescriptor());
      }

    }

    private long getRowsProcessed(MinorFragmentProfile profile) {
      long maxRecords = 0;
      for (OperatorProfile operatorProfile : profile.getOperatorProfileList()) {
        long records = 0;
        for (StreamProfile inputProfile :operatorProfile.getInputProfileList()) {
          if (inputProfile.hasRecords()) {
            records += inputProfile.getRecords();
          }
        }
        maxRecords = Math.max(maxRecords, records);
      }
      return maxRecords;
    }


    @Override
    public Iterator<FragmentInfo> getRunningFragments() {
      return Iterators.transform(fragmentExecutors.iterator(), new FragmentInfoTransformer());
    }

    @Override
    public Integer getCpuTrailingAverage(long id, int seconds) {
      return statsCollectorThread.getCpuTrailingAverage(id, seconds);
    }

    @Override
    public Integer getUserTrailingAverage(long id, int seconds) {
      return statsCollectorThread.getUserTrailingAverage(id, seconds);
    }
  }

  /**
   * If it is safe to exit, and the exitLatch is in use, signals it so that waitToExit() will
   * unblock.
   */
  private void indicateIfSafeToExit() {
    synchronized(this) {
      if (exitLatch != null) {
        if (fragmentExecutors.size() == 0) {
          exitLatch.countDown();
        }
      }
    }
  }

  public interface ExitCallback {
    void indicateIfSafeToExit();
  }

  @Override
  public void start() {

    bitContext = dbContext.get();

    this.executor = Executors.newCachedThreadPool();
    this.closeableExecutor = new CloseableExecutorService(executor);

    // start the internal rpc layer.
    this.allocator = context.getAllocator().newChildAllocator(
        "fragment-work-manager",
        context.getConfig().getLong("dremio.exec.rpc.bit.server.memory.data.reservation"),
        context.getConfig().getLong("dremio.exec.rpc.bit.server.memory.data.maximum"));

    final ExecToCoordTunnelCreator creator = new ExecToCoordTunnelCreator(fabricServiceProvider.get().getProtocol(Protocols.COORD_TO_EXEC));

    this.ticketDepot = workloadTicketDepotProvider.get();
    this.clerk = new QueriesClerk(ticketDepot, creator);

    final ExitCallback callback = new ExitCallback() {
      @Override
      public void indicateIfSafeToExit() {
        FragmentWorkManager.this.indicateIfSafeToExit();
      }
    };

    fragmentExecutors = new FragmentExecutors(creator, callback, pool.get(), bitContext.getOptionManager());

    final ExecConnectionCreator connectionCreator = new ExecConnectionCreator(fabricServiceProvider.get().registerProtocol(new ExecProtocol(bitContext.getConfig(), allocator, fragmentExecutors)));

    final FragmentExecutorBuilder builder = new FragmentExecutorBuilder(
        clerk,
        bitContext.getConfig(),
        bitContext.getClusterCoordinator(),
        executor,
        bitContext.getOptionManager(),
        creator,
        connectionCreator,
        bitContext.getClasspathScan(),
        bitContext.getPlanReader(),
        bitContext.getNamespaceService(SystemUser.SYSTEM_USERNAME),
        sources.get(),
        contextInformationFactory.get(),
        bitContext.getFunctionImplementationRegistry(),
        bitContext.getDecimalFunctionImplementationRegistry(),
        context.getNodeDebugContextProvider(),
        bitContext.getSpillService(),
        ClusterCoordinator.Role.fromEndpointRoles(identity.get().getRoles()));

    // register coord/exec message handling.
    bindingCreator.replace(CoordToExecHandler.class, new CoordToExecHandlerImpl(identity.get(), fragmentExecutors, builder));

    statusThread = new FragmentStatusThread(fragmentExecutors, clerk, creator);
    statusThread.start();
    Iterable<TaskPool.ThreadInfo> slicingThreads = pool.get().getSlicingThreads();
    Set<Long> slicingThreadIds = Sets.newHashSet();
    for (TaskPool.ThreadInfo slicingThread : slicingThreads) {
      slicingThreadIds.add(slicingThread.threadId);
    }
    statsCollectorThread = new ThreadsStatsCollector(slicingThreadIds);
    statsCollectorThread.start();

    // This makes sense only on executor nodes.
    if (bitContext.isExecutor() &&
        bitContext.getOptionManager().getOption(ExecConstants.ENABLE_HEAP_MONITORING)) {

      HeapClawBackStrategy strategy = new FailGreediestQueriesStrategy(fragmentExecutors, clerk);
      long thresholdPercentage =
          bitContext.getOptionManager().getOption(ExecConstants.HEAP_MONITORING_CLAWBACK_THRESH_PERCENTAGE);
      heapMonitorThread = new HeapMonitorThread(strategy, thresholdPercentage);
      heapMonitorThread.start();
    }

    final String prefix = "rpc";
    Metrics.newGauge(prefix + "bit.data.current", allocator::getAllocatedMemory);
    Metrics.newGauge(prefix + "bit.data.peak", allocator::getPeakMemoryAllocation);
  }

  public class ExecConnectionCreator {
    private final FabricRunnerFactory factory;

    public ExecConnectionCreator(FabricRunnerFactory factory) {
      super();
      this.factory = factory;
    }

    public ExecTunnel getTunnel(NodeEndpoint endpoint) {
      return new ExecTunnel(factory.getCommandRunner(endpoint.getAddress(), endpoint.getFabricPort()));
    }
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(statusThread, statsCollectorThread, heapMonitorThread,
      closeableExecutor, fragmentExecutors, allocator);
  }

}
