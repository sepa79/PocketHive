package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates config-update fan-out and bootstrap config handling for a swarm.
 * <p>
 * This helper owns the {@code config-update} wiring so that {@link SwarmLifecycleManager}
 * does not need to manipulate control-plane signals or pending bootstrap state directly.
 */
public final class SwarmConfigFanout {

  private static final Logger log = LoggerFactory.getLogger(SwarmConfigFanout.class);

  private static final long BOOTSTRAP_CONFIG_RESEND_DELAY_MS = 5_000L;

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {};

  private final ObjectMapper mapper;
  private final ControlPlanePublisher controlPublisher;
  private final String swarmId;
  private final String controllerRole;
  private final String controllerInstanceId;

  private final ConcurrentMap<String, PendingConfig> pendingConfigUpdates = new ConcurrentHashMap<>();

  public SwarmConfigFanout(ObjectMapper mapper,
                           ControlPlanePublisher controlPublisher,
                           String swarmId,
                           String controllerRole,
                           String controllerInstanceId) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.controlPublisher = Objects.requireNonNull(controlPublisher, "controlPublisher");
    this.swarmId = Objects.requireNonNull(swarmId, "swarmId");
    this.controllerRole = Objects.requireNonNull(controllerRole, "controllerRole");
    this.controllerInstanceId = Objects.requireNonNull(controllerInstanceId, "controllerInstanceId");
  }

  public void registerBootstrapConfig(String instance, String role, Map<String, Object> values) {
    if (instance == null || instance.isBlank() || values == null || values.isEmpty()) {
      return;
    }
    pendingConfigUpdates.put(instance, new PendingConfig(role, Map.copyOf(values)));
  }

  public void acknowledgeBootstrap(String instance) {
    if (instance == null || instance.isBlank()) {
      return;
    }
    PendingConfig pending = pendingConfigUpdates.get(instance);
    if (pending != null) {
      pending.markAcknowledged();
    }
  }

  public boolean hasPendingAcks() {
    for (PendingConfig pending : pendingConfigUpdates.values()) {
      if (pending.awaitingAck()) {
        return true;
      }
    }
    return false;
  }

  public Optional<String> handleConfigUpdateError(String instance, String error) {
    if (instance == null || instance.isBlank()) {
      return Optional.empty();
    }
    PendingConfig pending = pendingConfigUpdates.remove(instance);
    if (pending == null) {
      return Optional.empty();
    }
    String message = failureMessage(pending.role(), instance, error);
    return Optional.of(message);
  }

  public void publishBootstrapConfigIfNecessary(String instance, boolean force) {
    if (instance == null || instance.isBlank()) {
      return;
    }
    PendingConfig pending = pendingConfigUpdates.get(instance);
    if (pending == null) {
      return;
    }
    Map<String, Object> values = pending.values();
    if (values == null || values.isEmpty()) {
      pending.markAcknowledged();
      return;
    }
    long now = System.currentTimeMillis();
    if (!pending.shouldPublish(force, now)) {
      return;
    }
    ObjectNode payload = mapper.createObjectNode();
    var configNode = mapper.valueToTree(values);
    if (configNode instanceof ObjectNode objectNode) {
      payload.setAll(objectNode);
    } else {
      payload.set("config", configNode);
    }
    payload.put("commandTarget", "INSTANCE");
    payload.put("role", pending.role());
    payload.put("instance", instance);
    log.info("Publishing bootstrap config for role={} instance={}{}",
        pending.role(), instance, force ? " (initial)" : " (retry)");
    publishConfigUpdate(payload, "bootstrap-config");
    pending.markPublished(now);
  }

  public void publishConfigUpdate(ObjectNode data, String context) {
    Map<String, Object> dataMap = mapper.convertValue(data, MAP_TYPE);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("data", dataMap);
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();

    String rawCommandTarget = asTextValue(dataMap.remove("commandTarget"));
    String targetHint = asTextValue(dataMap.remove("target"));
    String scopeHint = asTextValue(dataMap.remove("scope"));
    String swarmHint = asTextValue(dataMap.remove("swarmId"));
    String roleHint = asTextValue(dataMap.remove("role"));
    String instanceHint = asTextValue(dataMap.remove("instance"));

    CommandTarget commandTarget = parseCommandTarget(rawCommandTarget, context);
    if (commandTarget == null) {
      commandTarget = commandTargetFromScope(scopeHint);
    }
    if (commandTarget == null) {
      commandTarget = commandTargetFromTarget(targetHint);
    }
    if (commandTarget == null) {
      commandTarget = CommandTarget.SWARM;
    }

    String resolvedSwarmId = normaliseSwarmHint(swarmHint);
    String role = roleHint;
    String instance = instanceHint;

    TargetSpec legacySpec = parseTargetSpec(targetHint);
    if (commandTarget == CommandTarget.INSTANCE) {
      if ((role == null || role.isBlank()) && legacySpec != null) {
        role = legacySpec.role();
      }
      if ((instance == null || instance.isBlank()) && legacySpec != null) {
        instance = legacySpec.instance();
      }
      if (role == null || role.isBlank()) {
        role = controllerRole;
      }
      if (instance == null || instance.isBlank()) {
        instance = controllerInstanceId;
      }
    } else if (commandTarget == CommandTarget.ROLE) {
      if ((role == null || role.isBlank()) && legacySpec != null) {
        role = legacySpec.role();
      }
      if (role == null || role.isBlank()) {
        throw new IllegalArgumentException("commandTarget=role requires role field");
      }
      instance = null;
    } else if (commandTarget == CommandTarget.SWARM || commandTarget == CommandTarget.ALL) {
      role = null;
      instance = null;
    }

    if (resolvedSwarmId == null) {
      resolvedSwarmId = swarmId;
    }

    ControlSignal signal = new ControlSignal(
        ControlPlaneSignals.CONFIG_UPDATE,
        correlationId,
        idempotencyKey,
        resolvedSwarmId,
        role,
        instance,
        controllerInstanceId,
        commandTarget,
        args);
    try {
      String payload = mapper.writeValueAsString(signal);
      String routingKey = ControlPlaneRouting.signal(
          ControlPlaneSignals.CONFIG_UPDATE, resolvedSwarmId, role, instance);
      log.info("{} config-update rk={} correlation={} payload {}",
          context, routingKey, correlationId, SwarmLifecycleManager.snippet(payload));
      controlPublisher.publishSignal(new SignalMessage(routingKey, payload));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize config-update signal", e);
    }
  }

  private static CommandTarget parseCommandTarget(String value, String context) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return CommandTarget.from(value);
    } catch (IllegalArgumentException ex) {
      log.warn("Ignoring unknown commandTarget {} on {} config-update", value, context);
      return null;
    }
  }

  private static CommandTarget commandTargetFromScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return null;
    }
    return switch (scope.trim().toLowerCase(Locale.ROOT)) {
      case "swarm" -> CommandTarget.SWARM;
      case "role" -> CommandTarget.ROLE;
      case "controller", "instance" -> CommandTarget.INSTANCE;
      case "all" -> CommandTarget.ALL;
      default -> null;
    };
  }

  private static CommandTarget commandTargetFromTarget(String target) {
    if (target == null || target.isBlank()) {
      return null;
    }
    String trimmed = target.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (lower.contains(".") || lower.contains(":")) {
      return CommandTarget.INSTANCE;
    }
    return switch (lower) {
      case "all" -> CommandTarget.ALL;
      case "swarm" -> CommandTarget.SWARM;
      case "controller", "instance" -> CommandTarget.INSTANCE;
      case "role" -> CommandTarget.ROLE;
      default -> null;
    };
  }

  private static TargetSpec parseTargetSpec(String target) {
    if (target == null || target.isBlank()) {
      return null;
    }
    String trimmed = target.trim();
    String[] parts;
    if (trimmed.contains(".")) {
      parts = trimmed.split("\\.", 2);
    } else if (trimmed.contains(":")) {
      parts = trimmed.split(":", 2);
    } else {
      return null;
    }
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      return null;
    }
    return new TargetSpec(parts[0], parts[1]);
  }

  private record TargetSpec(String role, String instance) {}

  private static final class PendingConfig {
    private final String role;
    private final Map<String, Object> values;
    private final AtomicBoolean awaitingAck = new AtomicBoolean(true);
    private volatile long lastPublishedAt = 0L;

    private PendingConfig(String role, Map<String, Object> values) {
      this.role = role;
      this.values = values;
    }

    String role() {
      return role;
    }

    Map<String, Object> values() {
      return values;
    }

    boolean awaitingAck() {
      return awaitingAck.get();
    }

    void markAcknowledged() {
      awaitingAck.set(false);
    }

    boolean shouldPublish(boolean force, long now) {
      if (!awaitingAck()) {
        return false;
      }
      if (force) {
        return true;
      }
      return now - lastPublishedAt >= BOOTSTRAP_CONFIG_RESEND_DELAY_MS;
    }

    void markPublished(long now) {
      lastPublishedAt = now;
    }
  }

  private static String failureMessage(String role, String instance, String reason) {
    String base = "Config update failed for role=" + role + " instance=" + instance;
    if (reason == null || reason.isBlank()) {
      return base;
    }
    return base + ": " + reason;
  }

  private static String asTextValue(Object value) {
    if (value instanceof String s) {
      String trimmed = s.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }
    return null;
  }

  private static String normaliseSwarmHint(String swarmHint) {
    if (swarmHint == null) {
      return null;
    }
    String trimmed = swarmHint.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return "ALL".equalsIgnoreCase(trimmed) ? null : trimmed;
  }
}
