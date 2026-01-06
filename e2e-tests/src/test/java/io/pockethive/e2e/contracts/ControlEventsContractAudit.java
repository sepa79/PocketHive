package io.pockethive.e2e.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import io.pockethive.controlplane.schema.ControlEventsSchemaValidator;
import io.pockethive.e2e.contracts.ControlPlaneMessageCapture.CapturedMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assertions;

public final class ControlEventsContractAudit {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final Set<String> LIFECYCLE_SIGNALS = Set.of(
      "swarm-template",
      "swarm-plan",
      "swarm-start",
      "swarm-stop",
      "swarm-remove"
  );

  private ControlEventsContractAudit() {
  }

  public static void assertAllValid(List<CapturedMessage> messages) {
    assertAllValid(messages, Duration.ZERO);
  }

  public static void assertAllValid(List<CapturedMessage> messages, Duration settleTime) {
    List<CapturedMessage> payloads = messages == null ? List.of() : messages;
    if (settleTime != null && !settleTime.isZero() && !settleTime.isNegative()) {
      try {
        Thread.sleep(settleTime.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    List<String> failures = new ArrayList<>();
    for (CapturedMessage message : payloads) {
      if (message == null) {
        continue;
      }
      String routingKey = message.routingKey();
      String json = message.payloadUtf8();
      try {
        JsonNode node = MAPPER.readTree(json);
        Set<ValidationMessage> errors = ControlEventsSchemaValidator.validate(node);
        if (!errors.isEmpty()) {
          failures.add("schema invalid rk=" + routingKey + " errors=" + errors);
          continue;
        }
        semanticChecks(node, routingKey, failures);
      } catch (Exception ex) {
        failures.add("parse failed rk=" + routingKey + " err=" + ex.getMessage() + " payload=" + snippet(json));
      }
    }

    if (!failures.isEmpty()) {
      Assertions.fail("Control-plane contract audit failed:\n- " + String.join("\n- ", failures));
    }
  }

  private static void semanticChecks(JsonNode node, String routingKey, List<String> failures) {
    if (node == null || failures == null) {
      return;
    }
    String kind = node.path("kind").asText("");
    String type = node.path("type").asText("");
    JsonNode scope = node.path("scope");
    String role = scope.path("role").asText("");
    String normalizedRole = role.trim().toLowerCase(Locale.ROOT);
    String normalizedType = type.trim().toLowerCase(Locale.ROOT);

    if ("outcome".equalsIgnoreCase(kind)) {
      JsonNode data = node.path("data");
      JsonNode status = data.path("status");
      if (status.isMissingNode() || status.isNull() || status.asText("").isBlank()) {
        failures.add("semantic invalid rk=" + routingKey
            + " reason=outcome missing data.status payload=" + snippet(node.toString()));
      }
      if ("config-update".equals(normalizedType)) {
        JsonNode enabled = data.path("enabled");
        if (enabled.isMissingNode() || enabled.isNull()) {
          failures.add("semantic invalid rk=" + routingKey
              + " reason=config-update outcome missing data.enabled payload=" + snippet(node.toString()));
        }
      }
    }

    if ("signal".equalsIgnoreCase(kind) && LIFECYCLE_SIGNALS.contains(normalizedType)) {
      String instance = scope.path("instance").asText("").trim();
      if (instance.isEmpty() || "all".equalsIgnoreCase(instance)) {
        failures.add("semantic invalid rk=" + routingKey
            + " reason=lifecycle signal uses ALL/blank instance payload=" + snippet(node.toString()));
      }
    }

    if ("metric".equalsIgnoreCase(kind)) {
      JsonNode data = node.path("data");
      if ("status-delta".equalsIgnoreCase(type)) {
        if (data.has("startedAt") || data.has("config") || data.has("io")) {
          failures.add("semantic invalid rk=" + routingKey
              + " reason=status-delta contains heavy fields payload=" + snippet(node.toString()));
        }
        JsonNode workers = data.path("context").path("workers");
        if (!workers.isMissingNode()) {
          failures.add("semantic invalid rk=" + routingKey
              + " reason=status-delta contains context.workers payload=" + snippet(node.toString()));
        }
      }

      JsonNode workers = data.path("context").path("workers");
      if (!"swarm-controller".equals(normalizedRole) && !workers.isMissingNode()) {
        failures.add("semantic invalid rk=" + routingKey
            + " reason=non-controller status contains context.workers role=" + role
            + " payload=" + snippet(node.toString()));
      }
      if ("swarm-controller".equals(normalizedRole) && "status-full".equalsIgnoreCase(type) && !workers.isArray()) {
        failures.add("semantic invalid rk=" + routingKey
            + " reason=swarm-controller status-full missing context.workers payload=" + snippet(node.toString()));
      }
    }
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 400) {
      return trimmed.substring(0, 400) + "…";
    }
    return trimmed;
  }
}
