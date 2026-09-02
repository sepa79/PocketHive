package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.manager.guard.BufferGuardSettings;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

/**
 * Responsibility: Normalize one config-update buffer-guard override against existing effective settings.
 * Must not: Read lifecycle state, apply settings, publish results, or invent fallback guard configuration.
 * Contract: Missing fields retain the explicit existing setting; enabled=false removes the configured guard.
 */
final class BufferGuardConfigOverrideMapper {

  private BufferGuardConfigOverrideMapper() {
  }

  static BufferGuardSettings apply(BufferGuardSettings base, JsonNode guardNode) {
    Objects.requireNonNull(base, "base");
    if (guardNode == null || !guardNode.isObject()) {
      throw new IllegalArgumentException("bufferGuard override must be an object");
    }
    Boolean enabled = optionalBoolean(guardNode, "enabled");
    if (Boolean.FALSE.equals(enabled)) {
      return null;
    }

    String queueAliasOverride = optionalText(guardNode, "queueAlias");
    if (queueAliasOverride != null && !queueAliasOverride.equalsIgnoreCase(base.queueAlias())) {
      throw new IllegalArgumentException(
          "Changing buffer guard queueAlias at runtime is not supported; edit the scenario plan instead");
    }

    BufferGuardSettings.Adjustment baseAdjustment = base.adjust();
    JsonNode adjustmentNode = optionalObject(guardNode, "adjust");
    BufferGuardSettings.Adjustment adjustment = new BufferGuardSettings.Adjustment(
        intOr(adjustmentNode, "maxIncreasePct", baseAdjustment.maxIncreasePct()),
        intOr(adjustmentNode, "maxDecreasePct", baseAdjustment.maxDecreasePct()),
        intOr(adjustmentNode, "minRatePerSec", baseAdjustment.minRatePerSec()),
        intOr(adjustmentNode, "maxRatePerSec", baseAdjustment.maxRatePerSec()));

    BufferGuardSettings.Prefill basePrefill = base.prefill();
    JsonNode prefillNode = optionalObject(guardNode, "prefill");
    Boolean prefillEnabled = optionalBoolean(prefillNode, "enabled");
    BufferGuardSettings.Prefill prefill = new BufferGuardSettings.Prefill(
        prefillEnabled == null ? basePrefill.enabled() : prefillEnabled,
        durationOr(prefillNode, "lookahead", basePrefill.lookahead()),
        intOr(prefillNode, "liftPct", basePrefill.liftPct()));

    BufferGuardSettings.Backpressure baseBackpressure = base.backpressure();
    JsonNode backpressureNode = optionalObject(guardNode, "backpressure");
    String backpressureAlias = optionalText(backpressureNode, "queueAlias");
    BufferGuardSettings.Backpressure backpressure = new BufferGuardSettings.Backpressure(
        backpressureAlias != null ? backpressureAlias : baseBackpressure.queueAlias(),
        baseBackpressure.queueName(),
        intOr(backpressureNode, "highDepth", baseBackpressure.highDepth()),
        intOr(backpressureNode, "recoveryDepth", baseBackpressure.recoveryDepth()),
        intOr(backpressureNode, "moderatorReductionPct", baseBackpressure.moderatorReductionPct()));

    return new BufferGuardSettings(
        base.queueAlias(),
        base.queueName(),
        base.targetRole(),
        base.initialRatePerSec(),
        intOr(guardNode, "targetDepth", base.targetDepth()),
        intOr(guardNode, "minDepth", base.minDepth()),
        intOr(guardNode, "maxDepth", base.maxDepth()),
        durationOr(guardNode, "samplePeriod", base.samplePeriod()),
        intOr(guardNode, "movingAverageWindow", base.movingAverageWindow()),
        adjustment,
        prefill,
        backpressure);
  }

  private static Duration durationOr(JsonNode node, String field, Duration current) {
    String value = optionalText(node, field);
    if (value == null) {
      return current;
    }
    try {
      return Duration.parse(value.toUpperCase(Locale.ROOT));
    } catch (DateTimeParseException failure) {
      throw new IllegalArgumentException(field + " must be an ISO-8601 duration", failure);
    }
  }

  private static JsonNode optionalObject(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException(field + " must be an object");
    }
    return value;
  }

  private static Boolean optionalBoolean(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException(field + " must be a non-blank string");
    }
    return value.asText().trim();
  }

  private static int intOr(JsonNode node, String field, int current) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null) {
      return current;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be a 32-bit integer");
    }
    return value.intValue();
  }
}
