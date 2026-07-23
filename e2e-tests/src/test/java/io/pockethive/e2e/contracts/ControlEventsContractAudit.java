package io.pockethive.e2e.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import io.pockethive.controlplane.schema.ControlEventsSchemaValidator;
import io.pockethive.e2e.contracts.ControlPlaneMessageCapture.CapturedMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Assertions;

public final class ControlEventsContractAudit {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final Set<String> LIFECYCLE_SIGNALS = Set.of(
      "swarm-start",
      "swarm-stop",
      "swarm-remove"
  );

  private ControlEventsContractAudit() {
  }

  public static void assertAllValid(List<CapturedMessage> messages) {
    assertAllValid(messages, List.of());
  }

  public static void assertAllValid(
      List<CapturedMessage> messages, List<ExpectedOperation> expectedOperations) {
    List<CapturedMessage> payloads = messages == null ? List.of() : messages;
    List<String> failures = new ArrayList<>();
    List<JsonNode> validEnvelopes = new ArrayList<>();
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
        routingIdentityCheck(node, routingKey, failures);
        semanticChecks(node, routingKey, failures);
        validEnvelopes.add(node);
      } catch (Exception ex) {
        failures.add("parse failed rk=" + routingKey + " err=" + ex.getMessage() + " payload=" + snippet(json));
      }
    }
    commandFlowCoverage(
        validEnvelopes, expectedOperations == null ? List.of() : expectedOperations, failures);

    if (!failures.isEmpty()) {
      Assertions.fail("Control-plane contract audit failed:\n- " + String.join("\n- ", failures));
    }
  }

  private static void commandFlowCoverage(
      List<JsonNode> envelopes,
      List<ExpectedOperation> expectedOperations,
      List<String> failures) {
    for (ExpectedOperation command : expectedOperations) {
      if (command.signalRequired() && !hasMatchingSignal(envelopes, command)) {
        failures.add("coverage missing family=signal type=" + command.type()
            + " correlationId=" + command.correlationId());
      }
      if (command.resultRequired() && !hasMatchingOperation(envelopes, "result", command)) {
        failures.add("coverage missing family=result type=" + command.type()
            + " correlationId=" + command.correlationId());
      }
      if (!hasMatchingOperation(envelopes, "outcome", command)) {
        failures.add("coverage missing family=outcome type=" + command.type()
            + " correlationId=" + command.correlationId());
      }
    }
  }

  private static boolean hasMatchingSignal(
      List<JsonNode> envelopes, ExpectedOperation command) {
    return envelopes.stream().anyMatch(envelope -> matchesOperation(envelope, "signal", command));
  }

  private static boolean hasMatchingOperation(
      List<JsonNode> envelopes, String kind, ExpectedOperation command) {
    return envelopes.stream().anyMatch(envelope -> matchesOperation(envelope, kind, command));
  }

  private static boolean matchesOperation(
      JsonNode envelope, String kind, ExpectedOperation command) {
    return kind.equals(envelope.path("kind").asText(""))
        && command.type().equals(envelope.path("type").asText(""))
        && command.swarmId().equals(envelope.path("scope").path("swarmId").asText(""))
        && command.correlationId().equals(envelope.path("correlationId").asText(""))
        && command.idempotencyKey().equals(envelope.path("idempotencyKey").asText(""));
  }

  public record ExpectedOperation(
      String type,
      String swarmId,
      String correlationId,
      String idempotencyKey,
      boolean signalRequired,
      boolean resultRequired) {

    public ExpectedOperation {
      type = requireExpectationText(type, "type");
      swarmId = requireExpectationText(swarmId, "swarmId");
      correlationId = requireExpectationText(correlationId, "correlationId");
      idempotencyKey = requireExpectationText(idempotencyKey, "idempotencyKey");
    }

    private static String requireExpectationText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " must not be blank");
      }
      return value.trim();
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
      if ("swarm-controller".equals(normalizedRole) && "status-full".equalsIgnoreCase(type)) {
        JsonNode bindings = data.path("context").path("bindings").path("work");
        JsonNode exchange = bindings.path("exchange");
        JsonNode edges = bindings.path("edges");
        if (!exchange.isTextual() || exchange.asText("").isBlank()) {
          failures.add("semantic invalid rk=" + routingKey
              + " reason=swarm-controller status-full missing context.bindings.work.exchange payload=" + snippet(node.toString()));
        }
        if (!edges.isArray()) {
          failures.add("semantic invalid rk=" + routingKey
              + " reason=swarm-controller status-full missing context.bindings.work.edges payload=" + snippet(node.toString()));
        }
      }
    }
  }

  private static void routingIdentityCheck(JsonNode node, String routingKey, List<String> failures) {
    String kind = node.path("kind").asText("");
    String type = node.path("type").asText("");
    JsonNode scope = node.path("scope");
    String[] parts = routingKey == null ? new String[0] : routingKey.split("\\.");
    if (parts.length < 5) {
      failures.add("routing invalid rk=" + routingKey + " reason=too few segments");
      return;
    }

    String routedSwarm = parts[parts.length - 3];
    String routedRole = parts[parts.length - 2];
    String routedInstance = parts[parts.length - 1];
    String routedType = String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length - 3));
    String expectedPrefix = "signal".equals(kind) ? "signal" : "event";
    String expectedType = "signal".equals(kind)
        ? type
        : ("event".equals(kind) ? type + "." + type : kind + "." + type);

    if (!expectedPrefix.equals(parts[0])
        || !expectedType.equals(routedType)
        || !scope.path("swarmId").asText("").equals(routedSwarm)
        || !scope.path("role").asText("").equals(routedRole)
        || !scope.path("instance").asText("").equals(routedInstance)) {
      failures.add("routing/envelope mismatch rk=" + routingKey
          + " expected=" + expectedPrefix + "." + expectedType + "."
          + scope.path("swarmId").asText("") + "."
          + scope.path("role").asText("") + "."
          + scope.path("instance").asText(""));
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
