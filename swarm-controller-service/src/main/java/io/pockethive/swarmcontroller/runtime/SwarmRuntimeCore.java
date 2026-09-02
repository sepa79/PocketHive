package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.manager.runtime.WorkerSpec;
import io.pockethive.manager.scenario.ScenarioLifecyclePort;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarmcontroller.SwarmLifecycleCore;
import io.pockethive.swarmcontroller.SwarmMetrics;
import io.pockethive.swarmcontroller.SwarmReadinessTracker;
import io.pockethive.swarmcontroller.WorkerStatusRequestCallback;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.scenario.SwarmScenarioCoordinator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: Coordinate the Swarm lifecycle using explicit runtime collaborators.
 * Must not: Consume transport messages, directly execute infrastructure operations, or duplicate readiness state.
 * Contract: Own one runtime lifecycle state machine and delegate infrastructure effects and projections.
 */
public final class SwarmRuntimeCore implements SwarmLifecycleCore {

  private static final Logger log = LoggerFactory.getLogger(SwarmRuntimeCore.class);

  private final ObjectMapper mapper;
  private final io.pockethive.manager.runtime.ConfigFanout configFanout;
  private final SwarmRuntimeInfrastructure infrastructure;
  private final SwarmQueueStatsCollector queueStatsCollector;
  private final SwarmRuntimeJournal runtimeJournal;
  private final SwarmReadinessTracker readinessTracker;
  private final String instanceId;
  private final String role;
  private final String swarmId;
  private final SwarmWorkerSpecFactory workerSpecFactory;
  private final SwarmScenarioCoordinator scenarios;
  private final SwarmWorkBindingsProjector workBindingsProjector;

  private volatile SwarmRuntimeContext runtimeContext;
  private volatile SwarmRuntimeState runtimeState;
  private TrafficPolicy trafficPolicy;
  private io.pockethive.swarm.model.lifecycle.WorkloadState workloadState =
      io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;
  private boolean controllerEnabled = false;
  private String template;

  public SwarmRuntimeCore(ObjectMapper mapper,
                          SwarmControllerProperties properties,
                          io.pockethive.manager.runtime.ConfigFanout configFanout,
                          SwarmJournal journal,
                          String instanceId,
                          SwarmWorkerSpecFactory workerSpecFactory,
                          SwarmRuntimeInfrastructure infrastructure,
                          SwarmQueueStatsCollector queueStatsCollector,
                          WorkerStatusRequestCallback statusRequests) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(properties, "properties");
    this.configFanout = Objects.requireNonNull(configFanout, "configFanout");
    this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure");
    this.queueStatsCollector = Objects.requireNonNull(queueStatsCollector, "queueStatsCollector");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.role = properties.getRole();
    this.swarmId = properties.getSwarmId();
    this.runtimeJournal = new SwarmRuntimeJournal(journal, swarmId, role, instanceId);
    this.workerSpecFactory = Objects.requireNonNull(workerSpecFactory, "workerSpecFactory");
    this.workBindingsProjector = new SwarmWorkBindingsProjector(properties.getTraffic());
    this.readinessTracker = new SwarmReadinessTracker(
        Objects.requireNonNull(statusRequests, "statusRequests"));
    ScenarioLifecyclePort scenarioLifecycle = new ScenarioLifecyclePort() {
      @Override
      public void enableAll() {
        SwarmRuntimeCore.this.setSwarmEnabled(true);
      }

      @Override
      public void setWorkEnabled(boolean enabled) {
        SwarmRuntimeCore.this.setSwarmEnabled(enabled);
      }
    };
    this.scenarios = new SwarmScenarioCoordinator(
        mapper,
        swarmId,
        configFanout,
        runtimeJournal,
        scenarioLifecycle,
        this::getWorkloadState,
        this::getMetrics);
  }

  @Override
  public void start(String planJson) {
    log.info("Starting swarm {}", swarmId);
    if (runtimeState == null || runtimeState.containersByRole().isEmpty()) {
      prepare(planJson);
    } else if (template == null) {
      template = planJson;
    }
    setControllerEnabled(true);
    setSwarmEnabled(true);
  }

  @Override
  public void prepare(String templateJson) {
    log.info("Preparing swarm {}", swarmId);
    try {
      this.template = templateJson;
      SwarmPlan plan = mapper.readValue(templateJson, SwarmPlan.class);
      this.trafficPolicy = plan.trafficPolicy();

      readinessTracker.reset();
      SwarmRuntimeContext analyzedContext = SwarmRuntimePlanAnalyzer.analyze(plan);
      for (Bee bee : plan.bees()) {
        readinessTracker.registerExpected(bee.role());
      }
      infrastructure.declareWorkTopology(analyzedContext.queueSuffixes());

      runtimeContext = analyzedContext;
      runtimeState = new SwarmRuntimeState(runtimeContext);

      List<WorkerSpec> workerSpecs = new ArrayList<>();
      SutEnvironment sutEnv = plan.sutEnvironment();
      Set<String> roles = new LinkedHashSet<>();
      for (Bee bee : analyzedContext.runnableBees()) {
        PlannedSwarmWorker plannedWorker = workerSpecFactory.plan(bee, sutEnv);
        WorkerSpec workerSpec = plannedWorker.spec();
        workerSpecs.add(workerSpec);
        roles.add(workerSpec.role());
        runtimeState.registerWorker(workerSpec.role(), workerSpec.id(), workerSpec.id());
        if (!plannedWorker.bootstrapConfig().isEmpty()) {
          configFanout.registerBootstrapConfig(
              workerSpec.id(), workerSpec.role(), plannedWorker.bootstrapConfig());
        }
      }
      runtimeJournal.workersPlanned(workerSpecs.size(), List.copyOf(roles));
      infrastructure.provisionWorkers(workerSpecs);
      runtimeJournal.workersProvisioned(workerSpecs.size());
      workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;
    } catch (JsonProcessingException e) {
      log.warn("Invalid template payload", e);
      runtimeJournal.templateInvalid(e);
    }
  }

  public void applyScenarioPlan(String planJson) {
    scenarios.applyPlan(planJson);
  }

  public void resetScenarioPlan() {
    scenarios.reset();
  }

  public void setScenarioRuns(Integer runs) {
    scenarios.setRunCount(runs);
  }

  @Override
  public void stop() {
    log.info("Stopping swarm {}", swarmId);
    setSwarmEnabled(false);
    setControllerEnabled(false);
    this.workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;
  }

  @Override
  public String sutId() {
    SwarmRuntimeContext ctx = runtimeContext;
    if (ctx == null) {
      return null;
    }
    SwarmPlan plan = ctx.plan();
    if (plan != null && plan.sutId() != null && !plan.sutId().isBlank()) {
      return plan.sutId().trim();
    }
    SutEnvironment sutEnvironment = ctx.sutEnvironment();
    if (sutEnvironment != null && sutEnvironment.id() != null && !sutEnvironment.id().isBlank()) {
      return sutEnvironment.id().trim();
    }
    return null;
  }

  @Override
  public List<io.pockethive.swarm.model.lifecycle.RemoveResource> remove() {
    log.info("Removing swarm {}", swarmId);
    List<io.pockethive.swarm.model.lifecycle.RemoveResource> removed = new ArrayList<>();
    setSwarmEnabled(false);
    trafficPolicy = null;
    SwarmRuntimeContext ctx = runtimeContext;
    SwarmRuntimeState state = runtimeState;
    if (state != null) {
      removed.addAll(infrastructure.removeWorkers(state.instancesByRole()));
    }

    Set<String> suffixes = ctx != null ? ctx.queueSuffixes() : infrastructure.declaredQueueSuffixes();
    removed.addAll(infrastructure.removeWorkTopology(suffixes));
    runtimeContext = null;
    runtimeState = null;

    workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.UNAVAILABLE;
    return List.copyOf(removed);
  }

  @Override
  public io.pockethive.swarm.model.lifecycle.WorkloadState getWorkloadState() {
    return workloadState;
  }

  @Override
  public void updateHeartbeat(String role, String instance) {
    updateHeartbeat(role, instance, System.currentTimeMillis());
  }

  public void updateHeartbeat(String role, String instance, long timestamp) {
    readinessTracker.recordHeartbeat(role, instance, timestamp);
    configFanout.publishBootstrapConfigIfNecessary(instance, false);
    // Scenario plans, guards and other manager-side helpers must not run while
    // the controller is disabled. Only tick the scenario engine once the
    // controller has been started via the normal lifecycle (REST
    // swarm-start / startSwarm or equivalent).
    if (controllerEnabled) {
      scenarios.tick();
    }
  }

  @Override
  public void recordStatusSnapshot(String role, String instance, boolean enabled) {
    readinessTracker.recordStatusSnapshot(role, instance, enabled);
  }

  @Override
  public long workerStatusObservationRevision() {
    return readinessTracker.statusObservationRevision();
  }

  @Override
  public boolean hasWorkerStatusSnapshotsAfter(long observationRevision) {
    return readinessTracker.hasSnapshotsAfter(observationRevision);
  }

  @Override
  public List<io.pockethive.swarm.model.lifecycle.Target> nonConvergedWorkersAfter(
      long observationRevision, boolean expectedEnabled) {
    return readinessTracker.nonConvergedWorkersAfter(observationRevision, expectedEnabled);
  }

  @Override
  public void updateEnabled(String role, String instance, boolean flag) {
    readinessTracker.recordEnabled(role, instance, flag);
  }

  @Override
  public SwarmMetrics getMetrics() {
    return readinessTracker.metrics();
  }

  @Override
  public List<io.pockethive.swarm.model.lifecycle.Target> expectedWorkers() {
    SwarmRuntimeState state = runtimeState;
    if (state == null) {
      return List.of();
    }
    return state.instancesByRole().entrySet().stream()
        .flatMap(entry -> entry.getValue().stream()
            .map(instance -> new io.pockethive.swarm.model.lifecycle.Target(entry.getKey(), instance)))
        .sorted(java.util.Comparator.comparing(io.pockethive.swarm.model.lifecycle.Target::role)
            .thenComparing(io.pockethive.swarm.model.lifecycle.Target::instance))
        .toList();
  }

  /**
   * Snapshot of scenario progress for status reporting.
   */
  public io.pockethive.swarmcontroller.scenario.TimelineScenario.Progress timelineScenarioProgress() {
    return scenarios.progress();
  }

  @Override
  public Map<String, QueueStats> snapshotQueueStats() {
    SwarmRuntimeContext ctx = runtimeContext;
    Set<String> suffixes = ctx != null ? ctx.queueSuffixes() : infrastructure.declaredQueueSuffixes();
    return queueStatsCollector.snapshot(suffixes);
  }

  @Override
  public Map<String, Object> workBindingsSnapshot() {
    SwarmRuntimeContext ctx = runtimeContext;
    SwarmPlan plan = ctx != null ? ctx.plan() : null;
    SwarmRuntimeState state = runtimeState;
    Map<String, List<String>> instancesByRole = state == null ? Map.of() : state.instancesByRole();
    return workBindingsProjector.project(plan, instancesByRole);
  }

  @Override
  public synchronized boolean markReady(String role, String instance) {
    configFanout.acknowledgeBootstrap(instance);
    return readinessTracker.markReady(role, instance);
  }

  @Override
  public synchronized boolean isReadyForWork() {
    return readinessTracker.isReadyForWork();
  }

  @Override
  public TrafficPolicy trafficPolicy() {
    return trafficPolicy;
  }

  @Override
  public synchronized Optional<String> handleConfigUpdateError(String role, String instance, String error) {
    Optional<String> message = configFanout.handleConfigUpdateError(instance, error);
    message.ifPresent(msg -> {
      log.warn(msg);
      workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.UNKNOWN;
    });
    return message;
  }

  @Override
  public synchronized void fail(String reason) {
    log.warn("Marking swarm {} failed: {}", swarmId, reason);
    workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.UNKNOWN;
  }

  @Override
  public boolean hasPendingConfigUpdates() {
    return configFanout.hasPendingAcks();
  }

  @Override
  public synchronized void enableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", true);
    log.info("Issuing swarm-wide enable config-update for swarm {} (role={} instance={})",
        swarmId, role, instanceId);
    configFanout.publishConfigUpdate(data, "enable");
    workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.RUNNING;
  }

  @Override
  public synchronized void setSwarmEnabled(boolean enabledFlag) {
    if (enabledFlag) {
      enableAll();
    } else {
      disableAll();
      workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;
    }
  }

  @Override
  public synchronized void setControllerEnabled(boolean enabled) {
    if (this.controllerEnabled == enabled) {
      return;
    }
    this.controllerEnabled = enabled;
    log.info("Swarm controller {} for swarm {} (role {})", enabled ? "enabled" : "disabled", swarmId, role);
  }

  private synchronized void disableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", false);
    log.info("Issuing swarm-wide disable config-update for swarm {} (role={} instance={})",
        swarmId, role, instanceId);
    configFanout.publishConfigUpdate(data, "disable");
  }
}
