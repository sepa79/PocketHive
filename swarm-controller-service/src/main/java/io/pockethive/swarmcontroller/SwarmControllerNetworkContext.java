package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.swarm.model.NetworkMode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;

/**
 * Responsibility: Own the Swarm Controller network context accepted from startup configuration and config updates.
 * Must not: Publish status, mutate lifecycle state, or construct config command outcomes.
 * Contract: Keep one explicit normalized value for SUT identity, network mode, and optional network profile.
 */
final class SwarmControllerNetworkContext {

  private static final String SUT_ID = "sutId";
  private static final String NETWORK_MODE = "networkMode";
  private static final String NETWORK_PROFILE_ID = "networkProfileId";

  private final SwarmLifecycle lifecycle;
  private final String controllerRole;
  private final String configuredSutId;
  private volatile NetworkMode networkMode;
  private volatile String networkProfileId;

  static SwarmControllerNetworkContext fromEnvironment(
      SwarmLifecycle lifecycle, String controllerRole) {
    return new SwarmControllerNetworkContext(
        lifecycle,
        controllerRole,
        trimToNull(System.getenv("POCKETHIVE_SUT_ID")),
        parseNetworkMode(System.getenv("POCKETHIVE_NETWORK_MODE")),
        trimToNull(System.getenv("POCKETHIVE_NETWORK_PROFILE_ID")));
  }

  SwarmControllerNetworkContext(
      SwarmLifecycle lifecycle,
      String controllerRole,
      String configuredSutId,
      NetworkMode networkMode,
      String networkProfileId) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.controllerRole = requireText(controllerRole, "controllerRole");
    this.configuredSutId = trimToNull(configuredSutId);
    this.networkMode = Objects.requireNonNull(networkMode, "networkMode");
    this.networkProfileId = trimToNull(networkProfileId);
  }

  boolean isOnlyNetworkContext(String targetRole, JsonNode data) {
    if (targetRole == null
        || !controllerRole.equalsIgnoreCase(targetRole)
        || data == null
        || !data.isObject()) {
      return false;
    }
    Iterator<String> fields = data.fieldNames();
    boolean foundNetworkField = false;
    while (fields.hasNext()) {
      String field = fields.next();
      if (SUT_ID.equals(field) || NETWORK_MODE.equals(field) || NETWORK_PROFILE_ID.equals(field)) {
        foundNetworkField = true;
      } else {
        return false;
      }
    }
    return foundNetworkField;
  }

  boolean applyOverride(JsonNode data) {
    if (data == null || !data.isObject()) {
      return false;
    }
    boolean changed = false;
    String requestedSutId = text(data.path(SUT_ID));
    String currentSutId = sutId();
    if (requestedSutId != null && currentSutId != null && !currentSutId.equals(requestedSutId)) {
      throw new IllegalArgumentException("sutId override does not match configured swarm SUT");
    }
    if (data.has(NETWORK_MODE)) {
      NetworkMode requestedMode = parseNetworkMode(text(data.path(NETWORK_MODE)));
      if (networkMode != requestedMode) {
        networkMode = requestedMode;
        changed = true;
      }
    }
    if (data.has(NETWORK_PROFILE_ID)) {
      String requestedProfileId = text(data.path(NETWORK_PROFILE_ID));
      if (!Objects.equals(networkProfileId, requestedProfileId)) {
        networkProfileId = requestedProfileId;
        changed = true;
      }
    }
    return normalizeProfileForMode() || changed;
  }

  String sutId() {
    String runtimeSutId = trimToNull(lifecycle.sutId());
    return runtimeSutId != null ? runtimeSutId : configuredSutId;
  }

  NetworkMode networkMode() {
    return networkMode;
  }

  String networkProfileId() {
    return networkProfileId;
  }

  private boolean normalizeProfileForMode() {
    if (networkMode != NetworkMode.DIRECT || networkProfileId == null) {
      return false;
    }
    networkProfileId = null;
    return true;
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() ? trimToNull(node.asText()) : null;
  }

  private static NetworkMode parseNetworkMode(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("POCKETHIVE_NETWORK_MODE/networkMode must be provided");
    }
    return NetworkMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  private static String requireText(String value, String field) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
