package io.pockethive.swarmcontroller;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: Build and publish canonical full and delta status projections for the Swarm Controller.
 * Must not: Consume transport messages, apply configuration, or coordinate lifecycle-triggered publication.
 * Contract: Derive status only from the lifecycle owner, accepted worker projections, and explicit runtime context.
 */
final class SwarmControllerStatusPublisher {

  private static final Logger log = LoggerFactory.getLogger(SwarmControllerStatusPublisher.class);
  private final SwarmLifecycle lifecycle;
  private final SwarmWorkerStatusHandler workerStatuses;
  private final SwarmHealthJournal healthJournal;
  private final SwarmControllerProperties properties;
  private final String swarmId;
  private final String role;
  private final String instanceId;
  private final ControlPlanePublisher publisher;
  private final Map<String, Object> runtimeMeta;
  private final String startupArtifactSha256;
  private final BooleanSupplier initialized;
  private final Instant startedAt;
  private final SwarmControllerNetworkContext networkContext;

  SwarmControllerStatusPublisher(
      SwarmLifecycle lifecycle,
      SwarmWorkerStatusHandler workerStatuses,
      SwarmHealthJournal healthJournal,
      SwarmControllerProperties properties,
      String instanceId,
      ControlPlanePublisher publisher,
      Map<String, Object> runtimeMeta,
      String startupArtifactSha256,
      BooleanSupplier initialized,
      Instant startedAt,
      SwarmControllerNetworkContext networkContext) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.workerStatuses = Objects.requireNonNull(workerStatuses, "workerStatuses");
    this.healthJournal = Objects.requireNonNull(healthJournal, "healthJournal");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.swarmId = properties.getSwarmId();
    this.role = properties.getRole();
    this.instanceId = requireText(instanceId, "instanceId");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.runtimeMeta = Collections.unmodifiableMap(
        new LinkedHashMap<>(Objects.requireNonNull(runtimeMeta, "runtimeMeta")));
    this.startupArtifactSha256 = requireText(startupArtifactSha256, "startupArtifactSha256");
    this.initialized = Objects.requireNonNull(initialized, "initialized");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.networkContext = Objects.requireNonNull(networkContext, "networkContext");
  }

  void publishFull() {
    refreshQueueMetrics();
    SwarmMetrics metrics = lifecycle.getMetrics();
    healthJournal.observe(metrics);
    WorkloadState workloadState = lifecycle.getWorkloadState();
    ConfirmationScope scope = ConfirmationScope.forInstance(swarmId, role, instanceId);
    String routingKey = ControlPlaneRouting.event(StatusMetric.KIND, StatusMetric.STATUS_FULL, scope);
    StatusEnvelopeBuilder builder = baseBuilder(StatusMetric.STATUS_FULL, workloadState, metrics)
        .filesystemEnabled(true)
        .data("startupArtifactSha256", startupArtifactSha256)
        .data("expectedWorkers", lifecycle.expectedWorkers())
        .data("startedAt", startedAt)
        .config(statusConfigSnapshot())
        .data("workers", workerStatuses.workersSnapshot())
        .data("swarmDiagnostics", workerStatuses.diagnosticsSnapshot())
        .data("bindings", Map.of("work", lifecycle.workBindingsSnapshot()));
    appendNetworkContext(builder);
    String controlQueue = properties.controlQueueName(role, instanceId);
    builder.controlIn(controlQueue)
        .controlRoutes(SwarmControllerRoutes.controllerControlRoutes(swarmId, role, instanceId))
        .controlOut(routingKey);
    appendTrafficDiagnostics(builder);
    publish(routingKey, builder.toEnvelope());
  }

  void publishDelta() {
    SwarmMetrics metrics = lifecycle.getMetrics();
    healthJournal.observe(metrics);
    WorkloadState workloadState = lifecycle.getWorkloadState();
    ConfirmationScope scope = ConfirmationScope.forInstance(swarmId, role, instanceId);
    String routingKey = ControlPlaneRouting.event(StatusMetric.KIND, StatusMetric.STATUS_DELTA, scope);
    StatusEnvelopeBuilder builder = baseBuilder(StatusMetric.STATUS_DELTA, workloadState, metrics);
    appendTrafficDiagnostics(builder);
    publish(routingKey, builder.toEnvelope());
  }

  private StatusEnvelopeBuilder baseBuilder(
      String type, WorkloadState workloadState, SwarmMetrics metrics) {
    return new StatusEnvelopeBuilder()
        .type(type)
        .role(role)
        .instance(instanceId)
        .origin(instanceId)
        .swarmId(swarmId)
        .workPlaneEnabled(false)
        .enabledRequired(false)
        .tpsEnabled(false)
        .data("controllerState", controllerState(workloadState))
        .data("workloadState", workloadState.name())
        .data("health", determineHealth(workloadState, metrics))
        .data("startupReady", startupReady())
        .data("watermarkAt", Instant.now())
        .data("scenario", scenarioProgress())
        .runtime(runtimeMeta);
  }

  private boolean startupReady() {
    return initialized.getAsBoolean()
        && lifecycle.isReadyForWork()
        && !lifecycle.hasPendingConfigUpdates();
  }

  private Map<String, Object> scenarioProgress() {
    Map<String, Object> snapshot = lifecycle.scenarioProgress();
    return snapshot != null ? snapshot : Map.of();
  }

  private void appendTrafficDiagnostics(StatusEnvelopeBuilder builder) {
    boolean guardActive = lifecycle.bufferGuardActive();
    String guardProblem = lifecycle.bufferGuardProblem();
    if (guardActive || guardProblem != null) {
      Map<String, Object> guardDiagnostics = new LinkedHashMap<>();
      guardDiagnostics.put("active", guardActive);
      if (guardProblem != null && !guardProblem.isBlank()) {
        guardDiagnostics.put("problem", guardProblem);
      }
      builder.data("bufferGuard", guardDiagnostics);
    }
  }

  private Map<String, Object> statusConfigSnapshot() {
    Map<String, Object> config = new LinkedHashMap<>();
    TrafficPolicy policy = effectiveTrafficPolicy();
    if (policy != null) {
      config.put("trafficPolicy", policy);
    }
    List<BufferGuardSettings> guards = lifecycle.bufferGuards();
    if (guards != null && !guards.isEmpty()) {
      config.put("bufferGuards", List.copyOf(guards));
    }
    return Map.copyOf(config);
  }

  private TrafficPolicy effectiveTrafficPolicy() {
    List<BufferGuardSettings> guards = lifecycle.bufferGuards();
    if (guards != null && !guards.isEmpty()) {
      return BufferGuardTrafficPolicyMapper.toTrafficPolicy(guards.getFirst());
    }
    return lifecycle.trafficPolicy();
  }

  private void appendNetworkContext(StatusEnvelopeBuilder builder) {
    String sutId = networkContext.sutId();
    if (sutId != null) {
      builder.data("sutId", sutId);
    }
    builder.data("networkMode", networkContext.networkMode().name());
    if (networkContext.networkProfileId() != null) {
      builder.data("networkProfileId", networkContext.networkProfileId());
    }
  }

  private void refreshQueueMetrics() {
    try {
      lifecycle.snapshotQueueStats();
    } catch (RuntimeException e) {
      log.warn("Failed to refresh swarm queue metrics before status-full for swarm {}", swarmId, e);
    }
  }

  private String controllerState(WorkloadState workloadState) {
    if (workloadState == null || workloadState == WorkloadState.UNKNOWN) {
      return ControllerState.FAILED.name();
    }
    return initialized.getAsBoolean()
        ? ControllerState.READY.name()
        : ControllerState.PROVISIONING.name();
  }

  private static String determineHealth(WorkloadState workloadState, SwarmMetrics metrics) {
    if (workloadState == null || workloadState == WorkloadState.UNKNOWN) {
      return Health.FAILED.name();
    }
    if (metrics != null && metrics.desired() > 0 && metrics.healthy() < metrics.desired()) {
      return Health.DEGRADED.name();
    }
    return Health.HEALTHY.name();
  }

  private void publish(String routingKey, StatusMetric payload) {
    log.debug("[CTRL] SEND status rk={} inst={} type={}", routingKey, instanceId, payload.type());
    publisher.publishEvent(new EventMessage(routingKey, payload));
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
