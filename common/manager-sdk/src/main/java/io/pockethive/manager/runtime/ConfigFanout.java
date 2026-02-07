package io.pockethive.manager.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.manager.ports.ControlPlanePort;
import io.pockethive.observability.ControlPlaneJson;
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
 * Transport-agnostic config-update fan-out and bootstrap config handling.
 * <p>
 * This is a shared variant of the Swarm Controller's SwarmConfigFanout that
 * uses {@link ControlPlanePort} instead of a concrete publisher so different
 * managers can reuse the same behaviour.
 */
public final class ConfigFanout {

  private static final Logger log = LoggerFactory.getLogger(ConfigFanout.class);

  private static final long BOOTSTRAP_CONFIG_RESEND_DELAY_MS = 5_000L;

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {};

  private final ObjectMapper mapper;
  private final ControlPlanePort controlPlane;
  private final String swarmId;
  private final String controllerInstanceId;

  private final ConcurrentMap<String, PendingConfig> pendingConfigUpdates = new ConcurrentHashMap<>();

  public ConfigFanout(ObjectMapper mapper,
                      ControlPlanePort controlPlane,
                      String swarmId,
                      String controllerInstanceId) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.swarmId = Objects.requireNonNull(swarmId, "swarmId");
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
    log.info("Publishing bootstrap config for role={} instance={}{}",
        pending.role(), instance, force ? " (initial)" : " (retry)");
    publishConfigUpdate(ControlScope.forInstance(swarmId, pending.role(), instance), payload, "bootstrap-config");
    pending.markPublished(now);
  }

  public void publishConfigUpdate(ObjectNode data, String context) {
    publishConfigUpdate(ControlScope.forSwarm(swarmId), data, context);
  }

  public void publishConfigUpdate(ControlScope target, ObjectNode data, String context) {
    Objects.requireNonNull(target, "target");
    String targetSwarmId = target.swarmId();
    if (targetSwarmId == null || targetSwarmId.isBlank()) {
      throw new IllegalArgumentException("target.swarmId must not be blank");
    }
    if (!swarmId.equals(targetSwarmId)) {
      throw new IllegalArgumentException(
          "ConfigFanout is bound to swarm " + swarmId + " but target.swarmId was " + targetSwarmId);
    }
    Map<String, Object> dataMap = mapper.convertValue(data, MAP_TYPE);
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();

    ControlSignal signal = io.pockethive.controlplane.messaging.ControlSignals.configUpdate(
        controllerInstanceId,
        target,
        correlationId,
        idempotencyKey,
        dataMap);
    String payload = ControlPlaneJson.write(signal, "config-update signal");
    String routingKey = ControlPlaneRouting.signal(
        ControlPlaneSignals.CONFIG_UPDATE, target.swarmId(), target.role(), target.instance());
    log.info("{} config-update rk={} correlation={} payload {}",
        context, routingKey, correlationId, payloadSnippet(payload));
    controlPlane.publishSignal(routingKey, payload);
  }

  private static String payloadSnippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }

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

}
