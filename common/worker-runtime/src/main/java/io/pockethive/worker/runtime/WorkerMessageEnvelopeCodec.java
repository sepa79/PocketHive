package io.pockethive.worker.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Serialises/deserialises {@link WorkerMessageEnvelope} instances while keeping payload content opaque.
 */
public final class WorkerMessageEnvelopeCodec {

  private static final Set<String> METADATA_FIELDS = Set.of(
      "messageId",
      "timestamp",
      "event",
      "kind",
      "version",
      "role",
      "instance",
      "swarmId",
      "location",
      "origin",
      "correlationId",
      "idempotencyKey"
  );

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

  private final ObjectMapper mapper;

  public WorkerMessageEnvelopeCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public WorkerMessageEnvelope decode(Map<String, Object> document) {
    Objects.requireNonNull(document, "document");
    JsonNode node = mapper.valueToTree(document);
    if (!node.isObject()) {
      throw new IllegalArgumentException("document must represent a JSON object");
    }
    return decode((ObjectNode) node);
  }

  public WorkerMessageEnvelope decode(JsonNode node) {
    Objects.requireNonNull(node, "node");
    if (!node.isObject()) {
      throw new IllegalArgumentException("payload must be a JSON object");
    }
    return decode((ObjectNode) node);
  }

  public Map<String, Object> encodeToMap(WorkerMessageEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    ObjectNode node = encodeToJson(envelope);
    return mapper.convertValue(node, MAP_TYPE);
  }

  public ObjectNode encodeToJson(WorkerMessageEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    ObjectNode node = mapper.createObjectNode();
    node.put("messageId", envelope.messageId());
    node.put("timestamp", envelope.timestamp().toString());
    putIfNotNull(node, "event", envelope.event());
    putIfNotNull(node, "kind", envelope.kind());
    putIfNotNull(node, "version", envelope.version());
    putIfNotNull(node, "role", envelope.role());
    putIfNotNull(node, "instance", envelope.instance());
    putIfNotNull(node, "swarmId", envelope.swarmId());
    putIfNotNull(node, "location", envelope.location());
    putIfNotNull(node, "origin", envelope.origin());
    putIfNotNull(node, "correlationId", envelope.correlationId());
    putIfNotNull(node, "idempotencyKey", envelope.idempotencyKey());
    if (envelope.payload() != null) {
      Iterator<Map.Entry<String, JsonNode>> fields = envelope.payload().fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        node.set(entry.getKey(), entry.getValue().deepCopy());
      }
    }
    return node;
  }

  private WorkerMessageEnvelope decode(ObjectNode node) {
    String messageId = requireText(node, "messageId");
    String timestampText = requireText(node, "timestamp");
    Instant timestamp = parseTimestamp(timestampText);
    ObjectNode payload = mapper.createObjectNode();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (METADATA_FIELDS.contains(entry.getKey())) {
        continue;
      }
      payload.set(entry.getKey(), entry.getValue().deepCopy());
    }
    return new WorkerMessageEnvelope(
        messageId,
        timestamp,
        textOrNull(node.get("event")),
        textOrNull(node.get("kind")),
        textOrNull(node.get("version")),
        textOrNull(node.get("role")),
        textOrNull(node.get("instance")),
        textOrNull(node.get("swarmId")),
        textOrNull(node.get("location")),
        textOrNull(node.get("origin")),
        textOrNull(node.get("correlationId")),
        textOrNull(node.get("idempotencyKey")),
        payload);
  }

  private static void putIfNotNull(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static String requireText(ObjectNode node, String field) {
    String value = textOrNull(node.get(field));
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be null or blank");
    }
    return value;
  }

  private static Instant parseTimestamp(String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("timestamp must be an ISO-8601 instant", ex);
    }
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String text = node.asText();
    return text != null && text.isBlank() ? null : text;
  }
}
