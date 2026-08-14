package io.pockethive.controlplane.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlPlaneEnvelope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import java.util.Objects;

/** Sole JSON Schema, serialization, deserialization and routing boundary for control-plane envelopes. */
public final class ControlPlaneCodec {

  private final ObjectMapper mapper;
  private final ControlPlaneSchemaValidator schemaValidator;

  private ControlPlaneCodec(ObjectMapper mapper, ControlPlaneSchemaValidator schemaValidator) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
  }

  public static ControlPlaneCodec create() {
    ObjectMapper mapper = JsonMapper.builder()
        .findAndAddModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    return new ControlPlaneCodec(mapper, ControlPlaneSchemaValidator.create(mapper));
  }

  public String encode(ControlPlaneEnvelope envelope, String routingKey) {
    Objects.requireNonNull(envelope, "envelope");
    requireRoutingKey(routingKey);
    try {
      JsonNode node = mapper.valueToTree(envelope);
      validate(node, routingKey);
      return mapper.writeValueAsString(node);
    } catch (ControlPlaneContractException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ControlPlaneContractException("Cannot serialize control-plane envelope", exception);
    }
  }

  public <T extends ControlPlaneEnvelope> T decode(
      String payload, String routingKey, Class<T> envelopeType) {
    if (payload == null || payload.isBlank()) {
      throw new ControlPlaneContractException("Control-plane payload must not be blank");
    }
    requireRoutingKey(routingKey);
    Objects.requireNonNull(envelopeType, "envelopeType");
    try {
      JsonNode node = mapper.readTree(payload);
      validate(node, routingKey);
      return mapper.treeToValue(node, envelopeType);
    } catch (ControlPlaneContractException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ControlPlaneContractException(
          "Cannot deserialize canonical control-plane envelope as " + envelopeType.getSimpleName(), exception);
    }
  }

  public ControlPlaneEnvelope decode(String payload, String routingKey) {
    JsonNode node = readAndValidate(payload, routingKey);
    Class<? extends ControlPlaneEnvelope> envelopeType = switch (requireText(node, "kind")) {
      case ControlSignal.KIND -> ControlSignal.class;
      case CommandResult.KIND -> CommandResult.class;
      case CommandOutcome.KIND -> CommandOutcome.class;
      case JournalEvent.KIND -> JournalEvent.class;
      case StatusMetric.KIND -> StatusMetric.class;
      case AlertMessage.KIND -> AlertMessage.class;
      default -> throw new ControlPlaneContractException(
          "Unsupported control-plane kind: " + requireText(node, "kind"));
    };
    try {
      return mapper.treeToValue(node, envelopeType);
    } catch (Exception exception) {
      throw new ControlPlaneContractException(
          "Cannot deserialize canonical control-plane envelope as " + envelopeType.getSimpleName(), exception);
    }
  }

  private void validate(JsonNode node, String routingKey) {
    schemaValidator.validate(node);
    validateRouting(node, routingKey);
  }

  private JsonNode readAndValidate(String payload, String routingKey) {
    if (payload == null || payload.isBlank()) {
      throw new ControlPlaneContractException("Control-plane payload must not be blank");
    }
    requireRoutingKey(routingKey);
    try {
      JsonNode node = mapper.readTree(payload);
      validate(node, routingKey);
      return node;
    } catch (ControlPlaneContractException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ControlPlaneContractException("Cannot parse control-plane JSON", exception);
    }
  }

  private static void validateRouting(JsonNode envelope, String routingKey) {
    String kind = requireText(envelope, "kind");
    String type = requireText(envelope, "type");
    JsonNode scope = envelope.required("scope");
    String swarmId = requireText(scope, "swarmId");
    String role = requireText(scope, "role");
    String instance = requireText(scope, "instance");

    RoutingKey parsed;
    String expectedType;
    if (ControlSignal.KIND.equals(kind)) {
      parsed = ControlPlaneRouting.parseSignal(routingKey);
      expectedType = type;
    } else {
      parsed = ControlPlaneRouting.parseEvent(routingKey);
      expectedType = eventRoutingType(kind, type);
    }
    if (parsed == null
        || !expectedType.equals(parsed.type())
        || !swarmId.equals(parsed.swarmId())
        || !role.equals(parsed.role())
        || !instance.equals(parsed.instance())) {
      throw new ControlPlaneContractException(
          "Control-plane routing key does not identify the envelope: " + routingKey);
    }
  }

  private static String eventRoutingType(String kind, String type) {
    return switch (kind) {
      case CommandResult.KIND, CommandOutcome.KIND, JournalEvent.KIND, StatusMetric.KIND ->
          kind + "." + type;
      case AlertMessage.KIND -> AlertMessage.TYPE + "." + type;
      default -> throw new ControlPlaneContractException("Unsupported control-plane kind: " + kind);
    };
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new ControlPlaneContractException("Control-plane envelope field must be text: " + field);
    }
    return value.asText();
  }

  private static void requireRoutingKey(String routingKey) {
    if (routingKey == null || routingKey.isBlank()) {
      throw new ControlPlaneContractException("Control-plane routing key must not be blank");
    }
  }

}
