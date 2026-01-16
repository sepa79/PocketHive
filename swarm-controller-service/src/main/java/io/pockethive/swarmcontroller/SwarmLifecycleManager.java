package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.WorkerSettings;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.docker.compute.DockerSingleNodeComputeAdapter;
import io.pockethive.docker.compute.DockerSwarmServiceComputeAdapter;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ConfigFanout;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarmcontroller.QueueStats;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager;
import io.pockethive.swarmcontroller.infra.docker.DockerWorkloadProvisioner;
import io.pockethive.swarmcontroller.infra.docker.WorkloadProvisioner;
import io.pockethive.swarmcontroller.runtime.SwarmRuntimeContext;
import io.pockethive.swarmcontroller.runtime.SwarmRuntimeCore;
import io.pockethive.swarmcontroller.runtime.SwarmRuntimeState;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.stereotype.Component;

/**
 * Spring wiring shell for {@link SwarmRuntimeCore}.
 * <p>
 * This class adapts Spring-managed infrastructure beans to the transport-agnostic
 * runtime core so tests and other runtimes can reuse the same lifecycle logic.
 */
@Component
public class SwarmLifecycleManager implements SwarmLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);

  private final SwarmRuntimeCore core;
  private final ObjectMapper mapper;
  private final io.pockethive.swarmcontroller.guard.BufferGuardCoordinator bufferGuard;
  private final io.pockethive.swarmcontroller.runtime.SwarmJournal journal;

  @Autowired
  public SwarmLifecycleManager(AmqpAdmin amqp,
                               ObjectMapper mapper,
                               DockerClient dockerClient,
                               DockerContainerClient docker,
                               RabbitTemplate rabbit,
                               RabbitProperties rabbitProperties,
                               @Qualifier("instanceId") String instanceId,
                               SwarmControllerProperties properties,
                               MeterRegistry meterRegistry,
                               io.pockethive.swarmcontroller.runtime.SwarmJournal journal) {
    this(amqp, mapper, dockerClient, docker, rabbit, rabbitProperties, instanceId, properties, meterRegistry,
        journal,
        deriveWorkerSettings(properties));
  }

  SwarmLifecycleManager(AmqpAdmin amqp,
                        ObjectMapper mapper,
                        DockerClient dockerClient,
                        DockerContainerClient docker,
                        RabbitTemplate rabbit,
                        RabbitProperties rabbitProperties,
                        String instanceId,
                        SwarmControllerProperties properties,
                        MeterRegistry meterRegistry,
                        io.pockethive.swarmcontroller.runtime.SwarmJournal journal,
                        WorkerSettings workerSettings) {
    Objects.requireNonNull(workerSettings, "workerSettings");
    this.mapper = mapper;
    this.journal = journal != null ? journal : io.pockethive.swarmcontroller.runtime.SwarmJournal.noop();
    ControlPlanePublisher controlPublisher =
        new AmqpControlPlanePublisher(rabbit, properties.getControlExchange());
    SwarmWorkTopologyManager topology = new SwarmWorkTopologyManager(amqp, properties);
    WorkloadProvisioner workloadProvisioner = new DockerWorkloadProvisioner(docker);
    ComputeAdapter computeAdapter;
    ComputeAdapterType adapterType = properties.getDocker() == null
        ? ComputeAdapterType.DOCKER_SINGLE
        : ComputeAdapterType.defaulted(properties.getDocker().computeAdapter());
    switch (adapterType) {
      case DOCKER_SINGLE -> computeAdapter = new DockerSingleNodeComputeAdapter(docker);
      case SWARM_STACK ->
          computeAdapter = new DockerSwarmServiceComputeAdapter(dockerClient, docker::resolveControlNetwork);
      default -> throw new IllegalStateException("Unsupported compute adapter type: " + adapterType);
    }
    SwarmQueueMetrics queueMetrics = new SwarmQueueMetrics(properties.getSwarmId(), meterRegistry);
    io.pockethive.manager.ports.QueueStatsPort queueStatsPort =
        new io.pockethive.swarmcontroller.runtime.SwarmQueueStatsPortAdapter(amqp);
    ConfigFanout configFanout =
        new ConfigFanout(mapper,
            new io.pockethive.swarmcontroller.runtime.SwarmControlPlanePortAdapter(controlPublisher),
            properties.getSwarmId(),
            instanceId);

    this.core = new SwarmRuntimeCore(
        amqp,
        mapper,
        docker,
        rabbitProperties,
        properties,
        meterRegistry,
        controlPublisher,
        topology,
        workloadProvisioner,
        computeAdapter,
        queueMetrics,
        configFanout,
        this.journal,
        instanceId);
    this.bufferGuard = new io.pockethive.swarmcontroller.guard.BufferGuardCoordinator(
        properties,
        queueStatsPort,
        meterRegistry,
        controlPublisher,
        mapper,
        instanceId);
  }

  private static WorkerSettings deriveWorkerSettings(SwarmControllerProperties properties) {
    Objects.requireNonNull(properties, "properties");
    SwarmControllerProperties.Traffic traffic = properties.getTraffic();
    SwarmControllerProperties.Pushgateway pushgateway = properties.getMetrics().pushgateway();
    var metrics = new io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.PushgatewaySettings(
        pushgateway.enabled(),
        pushgateway.baseUrl(),
        pushgateway.pushRate(),
        pushgateway.shutdownOperation());
    return new WorkerSettings(
        properties.getSwarmId(),
        properties.getControlExchange(),
        properties.getControlQueuePrefixBase(),
        traffic.hiveExchange(),
        properties.getRabbit().logsExchange(),
        properties.getRabbit().logging().enabled(),
        metrics);
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
  public void remove() {
    core.remove();
    bufferGuard.onRemove();
  }

  @Override
  public SwarmStatus getStatus() {
    return core.getStatus();
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
  public void updateEnabled(String role, String instance, boolean enabled) {
    core.updateEnabled(role, instance, enabled);
  }

  @Override
  public SwarmMetrics getMetrics() {
    return core.getMetrics();
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
