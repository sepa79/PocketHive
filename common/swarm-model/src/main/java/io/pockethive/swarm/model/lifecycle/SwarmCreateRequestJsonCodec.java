package io.pockethive.swarm.model.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.pockethive.swarm.model.NetworkMode;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical JSON Schema boundary for {@link SwarmCreateRequest}. */
public final class SwarmCreateRequestJsonCodec {

  private static final String SCHEMA_RESOURCE =
      "/io/pockethive/swarm/model/lifecycle/schema/swarm-lifecycle.schema.json";
  private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();
  private static final ObjectMapper INPUT_MAPPER = JsonMapper.builder().findAndAddModules().build();
  private static final JsonSchema CREATE_REQUEST_SCHEMA = loadCreateRequestSchema();

  private SwarmCreateRequestJsonCodec() {
  }

  public static SwarmCreateRequest fromJson(JsonNode node, ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper");
    validate(node);
    try {
      return SwarmCreateRequest.fromValidatedValues(
          requiredText(node, "templateId"),
          requiredText(node, "idempotencyKey"),
          requiredBoolean(node, "autoPullImages"),
          nullableText(node, "sutId"),
          nullableText(node, "variablesProfileId"),
          mapper.treeToValue(node.required("networkMode"), NetworkMode.class),
          nullableText(node, "networkProfileId"));
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new SwarmLifecycleContractException(
          "Cannot deserialize canonical swarm create request", exception);
    }
  }

  static SwarmCreateRequest fromArguments(
      String templateId,
      String idempotencyKey,
      boolean autoPullImages,
      String sutId,
      String variablesProfileId,
      NetworkMode networkMode,
      String networkProfileId) {
    ObjectNode node = INPUT_MAPPER.createObjectNode();
    putNullableText(node, "templateId", templateId);
    putNullableText(node, "idempotencyKey", idempotencyKey);
    node.put("autoPullImages", autoPullImages);
    putNullableText(node, "sutId", sutId);
    putNullableText(node, "variablesProfileId", variablesProfileId);
    if (networkMode == null) {
      node.putNull("networkMode");
    } else {
      node.put("networkMode", networkMode.name());
    }
    putNullableText(node, "networkProfileId", networkProfileId);
    return fromJson(node, INPUT_MAPPER);
  }

  public static void validate(JsonNode node) {
    Set<ValidationMessage> errors = CREATE_REQUEST_SCHEMA.validate(Objects.requireNonNull(node, "node"));
    if (!errors.isEmpty()) {
      throw new SwarmLifecycleContractException(
          "Swarm create request schema validation failed: " + errors);
    }
  }

  private static String requiredText(JsonNode node, String field) {
    return node.required(field).textValue();
  }

  private static boolean requiredBoolean(JsonNode node, String field) {
    return node.required(field).booleanValue();
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.required(field);
    return value.isNull() ? null : value.textValue();
  }

  private static void putNullableText(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static JsonSchema loadCreateRequestSchema() {
    try {
      URL schemaResource = SwarmCreateRequestJsonCodec.class.getResource(SCHEMA_RESOURCE);
      if (schemaResource == null) {
        throw new IllegalStateException("Missing packaged lifecycle schema: " + SCHEMA_RESOURCE);
      }
      JsonNode schemaDocument = SCHEMA_MAPPER.readTree(schemaResource);
      JsonNode schemaIdNode = schemaDocument.get("$id");
      if (schemaIdNode == null || !schemaIdNode.isTextual() || schemaIdNode.asText().isBlank()) {
        throw new IllegalStateException("Packaged lifecycle schema has no non-blank $id");
      }
      String schemaId = schemaIdNode.asText();
      ObjectNode createRequestSchema = SCHEMA_MAPPER.createObjectNode();
      createRequestSchema.put("$schema", schemaDocument.path("$schema").asText());
      createRequestSchema.put("$ref", schemaId + "#/$defs/SwarmCreateRequest");
      JsonSchemaFactory factory = JsonSchemaFactory.builder(
              JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
          .objectMapper(SCHEMA_MAPPER)
          .addUriMappings(Map.of(schemaId, schemaResource.toExternalForm()))
          .build();
      return factory.getSchema(createRequestSchema);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot load packaged canonical lifecycle schema", exception);
    }
  }
}
