package io.pockethive.swarmcontroller;

import io.pockethive.swarmcontroller.config.SwarmControllerMetricsProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.controlplane.spring.AmqpControlPlanePublisher;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.MetricsSettings;
import io.pockethive.controlplane.spring.WorkerSettings;
import io.pockethive.manager.ports.ComputeHost;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.swarmcontroller.runtime.SwarmQueueStatsCollector;
import io.pockethive.swarmcontroller.runtime.SwarmRuntimeCore;
import io.pockethive.swarmcontroller.runtime.SwarmRuntimeInfrastructure;
import io.pockethive.swarmcontroller.runtime.SwarmWorkerSpecFactory;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationPort;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import io.pockethive.rabbit.api.RabbitConnectionSettings;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Adapt Spring-managed infrastructure to the transport-agnostic {@link SwarmRuntimeCore}.
 * Must not: Duplicate lifecycle, readiness, or worker-observation state owned by the runtime core.
 * Contract: RESP-CONTROLLER-CONTROL — docs/architecture/runtime-responsibilities.md#resp-controller-control.
 * Delegate every {@link SwarmLifecycle} capability to one core instance.
 * Connection settings: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
@Component
public class SwarmLifecycleManager implements SwarmLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);

  private final SwarmRuntimeCore core;
  private final ObjectMapper mapper;
  private final io.pockethive.swarmcontroller.guard.BufferGuardCoordinator bufferGuard;
  private final io.pockethive.swarmcontroller.runtime.SwarmJournal journal;

  @Autowired
  public SwarmLifecycleManager(
      @Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.CONTROL) RabbitResources amqp,
      io.pockethive.topology.work.WorkPlaneResources workResources,
                               ObjectMapper mapper,
                               ComputeAdapter computeAdapter,
                               ComputeHost docker,
                               @org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER) RabbitPublisher rabbit,
                               ControlPlaneCodec controlPlaneCodec,
                               RabbitConnectionSettings rabbitConnection,
                               @Qualifier("instanceId") String instanceId,
                               SwarmControllerProperties properties,
                               MeterRegistry meterRegistry,
                               io.pockethive.swarmcontroller.runtime.SwarmJournal journal,
                               ClickHouseSinkProperties clickHouseSink,
                               io.pockethive.controlplane.filesystem.RuntimeFilesystemMount runtimeFilesystemMount,
                               WorkerWorkConfigurationPort workConfiguration,
                        io.pockethive.topology.work.WorkTopologyResolver workTopologyResolver) {
    this(amqp, workResources, mapper, computeAdapter, docker, rabbit, controlPlaneCodec, rabbitConnection, instanceId, properties, meterRegistry,
        journal,
        deriveWorkerSettings(properties),
        clickHouseSink,
        runtimeFilesystemMount, workConfiguration, workTopologyResolver);
  }

  SwarmLifecycleManager(
      @Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.CONTROL) RabbitResources amqp,
      io.pockethive.topology.work.WorkPlaneResources workResources,
                        ObjectMapper mapper,
                        ComputeAdapter computeAdapter,
                        ComputeHost docker,
                        @org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER) RabbitPublisher rabbit,
                        ControlPlaneCodec controlPlaneCodec,
                        RabbitConnectionSettings rabbitConnection,
                        String instanceId,
                        SwarmControllerProperties properties,
                        MeterRegistry meterRegistry,
                        io.pockethive.swarmcontroller.runtime.SwarmJournal journal,
                        WorkerSettings workerSettings,
                        ClickHouseSinkProperties clickHouseSink,
                        io.pockethive.controlplane.filesystem.RuntimeFilesystemMount runtimeFilesystemMount,
                        WorkerWorkConfigurationPort workConfiguration,
                        io.pockethive.topology.work.WorkTopologyResolver workTopologyResolver) {
    Objects.requireNonNull(workerSettings, "workerSettings");
    this.mapper = mapper;
    this.journal = Objects.requireNonNull(journal, "journal");
    ControlPlanePublisher controlPublisher = new AmqpControlPlanePublisher(
        rabbit, properties.getControlExchange(), Objects.requireNonNull(controlPlaneCodec, "controlPlaneCodec"));
    SwarmQueueMetrics queueMetrics = new SwarmQueueMetrics(properties.getSwarmId(), meterRegistry);
    io.pockethive.manager.ports.QueueStatsPort queueStatsPort =
        new io.pockethive.swarmcontroller.runtime.SwarmQueueStatsPortAdapter(workResources);
    ConfigFanout configFanout =
        new ConfigFanout(
            mapper,
            new io.pockethive.swarmcontroller.runtime.SwarmControlPlanePortAdapter(controlPublisher),
            properties.getSwarmId(),
            instanceId);
    SwarmWorkerSpecFactory workerSpecFactory = new SwarmWorkerSpecFactory(
        properties,
        workerSettings,
        rabbitConnection,
        docker::resolveControlNetwork,
        clickHouseSink,
        runtimeFilesystemMount,
        () -> requireEnvValue("POCKETHIVE_TEMPLATE_ID"),
        workConfiguration);
    SwarmRuntimeInfrastructure runtimeInfrastructure = new SwarmRuntimeInfrastructure(
        amqp, properties, workResources, computeAdapter, queueMetrics);
    SwarmQueueStatsCollector queueStatsCollector = new SwarmQueueStatsCollector(
        queueStatsPort, queueMetrics);
    WorkerStatusRequestCallback statusRequests = new SwarmWorkerStatusRequestPublisher(
        controlPublisher, properties.getSwarmId(), instanceId);

    this.core = new SwarmRuntimeCore(
        mapper,
        properties,
        configFanout,
        this.journal,
        instanceId,
        workerSpecFactory,
        runtimeInfrastructure,
        queueStatsCollector,
        statusRequests, workTopologyResolver);
    this.bufferGuard = new io.pockethive.swarmcontroller.guard.BufferGuardCoordinator(
        properties,
        queueStatsPort,
        meterRegistry,
        controlPublisher,
        mapper,
        instanceId, workTopologyResolver, core::workTopology);
  }

  private static WorkerSettings deriveWorkerSettings(SwarmControllerProperties properties) {
    Objects.requireNonNull(properties, "properties");
    SwarmControllerMetricsProperties propertiesMetrics = properties.getMetrics();
    var metrics = new MetricsSettings(
        propertiesMetrics.adapter(),
        propertiesMetrics.publishInterval(),
        propertiesMetrics.clickHouse());
    return new WorkerSettings(
        properties.getSwarmId(),
        requireEnvValue("POCKETHIVE_JOURNAL_RUN_ID"),
        properties.getControlExchange(),
        properties.getControlQueuePrefixBase(),
        metrics);
  }

  private static String requireEnvValue(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required environment variable: " + key);
    }
    return value.trim();
  }

  @Override
  public void prepare(String templateJson) {
    core.prepare(templateJson);
    bufferGuard.configureFromTemplate(templateJson);
  }

  @Override
  public void applyScenarioPlan(String planJson) {
    core.applyScenarioPlan(planJson);
  }

  @Override
  public void start(String planJson) {
    core.start(planJson);
    bufferGuard.onSwarmEnabled(true);
  }

  @Override
  public void stop() {
    core.stop();
    bufferGuard.onSwarmEnabled(false);
  }

  @Override
  public java.util.List<io.pockethive.swarm.model.lifecycle.RemoveResource> remove() {
    java.util.List<io.pockethive.swarm.model.lifecycle.RemoveResource> removed = core.remove();
    bufferGuard.onRemove();
    return removed;
  }

  @Override
  public io.pockethive.swarm.model.lifecycle.WorkloadState getWorkloadState() {
    return core.getWorkloadState();
  }

  @Override
  public boolean markReady(String role, String instance) {
    return core.markReady(role, instance);
  }

  @Override
  public void updateHeartbeat(String role, String instance) {
    core.updateHeartbeat(role, instance);
  }

  void updateHeartbeat(String role, String instance, long timestamp) {
    core.updateHeartbeat(role, instance, timestamp);
  }

  @Override
  public void recordStatusSnapshot(String role, String instance, boolean enabled) {
    core.recordStatusSnapshot(role, instance, enabled);
  }

  @Override
  public long workerStatusObservationRevision() {
    return core.workerStatusObservationRevision();
  }

  @Override
  public boolean hasWorkerStatusSnapshotsAfter(long observationRevision) {
    return core.hasWorkerStatusSnapshotsAfter(observationRevision);
  }

  @Override
  public java.util.List<io.pockethive.swarm.model.lifecycle.Target> nonConvergedWorkersAfter(
      long observationRevision, boolean expectedEnabled) {
    return core.nonConvergedWorkersAfter(observationRevision, expectedEnabled);
  }

  @Override
  public void updateEnabled(String role, String instance, boolean enabled) {
    core.updateEnabled(role, instance, enabled);
  }

  @Override
  public SwarmMetrics getMetrics() {
    return core.getMetrics();
  }

  @Override
  public java.util.List<io.pockethive.swarm.model.lifecycle.Target> expectedWorkers() {
    return core.expectedWorkers();
  }

  @Override
  public Map<String, QueueStats> snapshotQueueStats() {
    return core.snapshotQueueStats();
  }

  @Override
  public Map<String, Object> workBindingsSnapshot() {
    return core.workBindingsSnapshot();
  }

  @Override
  public void enableAll() {
    core.enableAll();
  }

  @Override
  public void setSwarmEnabled(boolean enabled) {
    core.setSwarmEnabled(enabled);
    bufferGuard.onSwarmEnabled(enabled);
  }

  @Override
  public java.util.List<io.pockethive.manager.guard.BufferGuardSettings> bufferGuards() {
    return bufferGuard.currentSettings();
  }

  @Override
  public void configureBufferGuards(java.util.List<io.pockethive.manager.guard.BufferGuardSettings> settings) {
    bufferGuard.configure(settings);
  }

  @Override
  public boolean isReadyForWork() {
    return core.isReadyForWork();
  }

  @Override
  public TrafficPolicy trafficPolicy() {
    return core.trafficPolicy();
  }

  @Override
  public String sutId() {
    return core.sutId();
  }

  public boolean bufferGuardActive() {
    return bufferGuard.isActive();
  }

  public String bufferGuardProblem() {
    return bufferGuard.lastProblem();
  }

  @Override
  public Optional<String> handleConfigUpdateError(String role, String instance, String error) {
    return core.handleConfigUpdateError(role, instance, error);
  }

  @Override
  public void fail(String reason) {
    core.fail(reason);
  }

  @Override
  public boolean hasPendingConfigUpdates() {
    return core.hasPendingConfigUpdates();
  }

  @Override
  public void setControllerEnabled(boolean enabled) {
    core.setControllerEnabled(enabled);
  }

  @Override
  public java.util.Map<String, Object> scenarioProgress() {
    io.pockethive.swarmcontroller.scenario.TimelineScenario.Progress p = core.timelineScenarioProgress();
    if (p == null) {
      return java.util.Map.of();
    }
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    if (p.lastStepId != null) {
      m.put("lastStepId", p.lastStepId);
    }
    if (p.lastStepName != null) {
      m.put("lastStepName", p.lastStepName);
    }
    m.put("firedStepIds", p.firedStepIds);
    m.put("elapsedMillis", p.elapsedMillis);
    if (p.nextStepId != null) {
      m.put("nextStepId", p.nextStepId);
    }
    if (p.nextStepName != null) {
      m.put("nextStepName", p.nextStepName);
    }
    if (p.nextDueMillis != null) {
      m.put("nextDueMillis", p.nextDueMillis);
    }
    if (p.totalRuns != null) {
      m.put("totalRuns", p.totalRuns);
    }
    if (p.runsRemaining != null) {
      m.put("runsRemaining", p.runsRemaining);
    }
    return m;
  }

  @Override
  public void resetScenarioPlan() {
    core.resetScenarioPlan();
  }

  @Override
  public void setScenarioRuns(Integer runs) {
    core.setScenarioRuns(runs);
  }

  static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }
}
