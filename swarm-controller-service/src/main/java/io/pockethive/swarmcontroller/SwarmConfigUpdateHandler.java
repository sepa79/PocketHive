package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: Apply accepted Swarm Controller config-update commands through canonical lifecycle owners.
 * Must not: Decode transport, decide routing acceptance, construct result contracts, or own lifecycle state.
 * Contract: Admission precedes mutation; successful mutations publish one canonical config result.
 */
final class SwarmConfigUpdateHandler {

  private static final Logger log = LoggerFactory.getLogger(SwarmConfigUpdateHandler.class);

  private final SwarmLifecycle lifecycle;
  private final ObjectMapper mapper;
  private final String controllerRole;
  private final String controllerInstance;
  private final SwarmControllerNetworkContext networkContext;
  private final SwarmControllerStatusPublisher statusPublisher;
  private final SwarmCommandReadiness readiness;
  private final SwarmControllerResultPublisher results;

  SwarmConfigUpdateHandler(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      String controllerRole,
      String controllerInstance,
      SwarmControllerNetworkContext networkContext,
      SwarmControllerStatusPublisher statusPublisher,
      SwarmCommandReadiness readiness,
      SwarmControllerResultPublisher results) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.controllerRole = requireText(controllerRole, "controllerRole");
    this.controllerInstance = requireText(controllerInstance, "controllerInstance");
    this.networkContext = Objects.requireNonNull(networkContext, "networkContext");
    this.statusPublisher = Objects.requireNonNull(statusPublisher, "statusPublisher");
    this.readiness = Objects.requireNonNull(readiness, "readiness");
    this.results = Objects.requireNonNull(results, "results");
  }

  void handle(ControlSignal signal, String swarmId) {
    Objects.requireNonNull(signal, "signal");
    ControlScope scope = signal.scope();
    String targetRole = scope.role();
    boolean roleAll = ControlScope.isAll(targetRole);
    boolean instanceAll = ControlScope.isAll(scope.instance());
    if (controllerInstance.equalsIgnoreCase(signal.origin()) && roleAll && instanceAll) {
      log.debug("Ignoring self-issued broadcast config-update; corr={}", signal.correlationId());
      return;
    }

    JsonNode data = mapper.valueToTree(signal.data());
    boolean networkContextOnly = networkContext.isOnlyNetworkContext(targetRole, data);
    SwarmCommandReadinessSnapshot snapshot = readiness.snapshot();
    if (!snapshot.accepts(!networkContextOnly)) {
      reject(signal, swarmId, snapshot);
      return;
    }

    try {
      Boolean enabled = optionalBoolean(data, "enabled");
      boolean networkContextChanged = networkContext.applyOverride(data);
      applyBufferGuardOverride(data);

      boolean scenarioChanged = false;
      if (controllerRole.equalsIgnoreCase(targetRole)) {
        if (enabled != null) {
          lifecycle.setSwarmEnabled(enabled);
          statusPublisher.publishDelta();
        }
        scenarioChanged = applyScenarioOverrides(data);
      }

      if (scenarioChanged || networkContextChanged) {
        statusPublisher.publishDelta();
      }
    } catch (Exception failure) {
      log.warn("config update", failure);
      results.publishFailure(signal, ControlPlaneSignals.CONFIG_UPDATE, failure);
      return;
    }
    results.publishConfig(signal, TerminalStatus.SUCCEEDED);
  }

  private void reject(
      ControlSignal signal,
      String swarmId,
      SwarmCommandReadinessSnapshot snapshot) {
    log.warn(
        "[CTRL] command rejected operation={} phase={} code={} message={} swarmId={} role={} instance={} "
            + "correlationId={} idempotencyKey={} retryable={} initialized={} ready={} pendingConfigUpdates={} "
            + "status={}",
        ControlPlaneSignals.CONFIG_UPDATE,
        ControlPlaneSignals.CONFIG_UPDATE,
        "not-ready",
        "Swarm controller is not ready for this operation",
        swarmId,
        controllerRole,
        controllerInstance,
        signal.correlationId(),
        signal.idempotencyKey(),
        true,
        snapshot.initialized(),
        snapshot.ready(),
        snapshot.pendingConfigUpdates(),
        snapshot.workloadState() == null ? "unknown" : snapshot.workloadState().name());
    results.publishConfig(signal, TerminalStatus.REJECTED);
  }

  private void applyBufferGuardOverride(JsonNode data) {
    JsonNode trafficPolicy = data.get("trafficPolicy");
    if (trafficPolicy == null) {
      return;
    }
    if (!trafficPolicy.isObject()) {
      throw new IllegalArgumentException("trafficPolicy must be an object");
    }
    JsonNode guardNode = trafficPolicy.get("bufferGuard");
    if (guardNode == null) {
      return;
    }
    if (!guardNode.isObject()) {
      throw new IllegalArgumentException("trafficPolicy.bufferGuard must be an object");
    }
    List<BufferGuardSettings> currentGuards = lifecycle.bufferGuards();
    if (currentGuards.isEmpty()) {
      throw new IllegalStateException(
          "Cannot apply bufferGuard override because no guard is configured by the scenario");
    }
    BufferGuardSettings updated =
        BufferGuardConfigOverrideMapper.apply(currentGuards.getFirst(), guardNode);
    lifecycle.configureBufferGuards(updated == null ? List.of() : List.of(updated));
  }

  private boolean applyScenarioOverrides(JsonNode data) {
    JsonNode scenario = data.get("scenario");
    if (scenario == null) {
      return false;
    }
    if (!scenario.isObject()) {
      throw new IllegalArgumentException("scenario must be an object");
    }
    boolean changed = false;
    if (scenario.has("runs")) {
      JsonNode runsNode = scenario.get("runs");
      if (!runsNode.isIntegralNumber() || !runsNode.canConvertToInt() || runsNode.intValue() < 1) {
        throw new IllegalArgumentException("scenario.runs must be a positive 32-bit integer");
      }
      lifecycle.setScenarioRuns(runsNode.intValue());
      changed = true;
    }
    Boolean reset = optionalBoolean(scenario, "reset");
    if (Boolean.TRUE.equals(reset)) {
      lifecycle.resetScenarioPlan();
      changed = true;
    }
    return changed;
  }

  private static Boolean optionalBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
