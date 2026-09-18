package io.pockethive.controlplane.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Internal schema component of the canonical control-plane JSON boundary. */
final class ControlPlaneSchemaValidator {

  private static final String SCHEMA_ROOT = "/io/pockethive/controlplane/schema/";
  private static final String CONTROL_SCHEMA = SCHEMA_ROOT + "control-events.schema.json";
  private static final String LIFECYCLE_SCHEMA = SCHEMA_ROOT + "swarm-lifecycle.schema.json";
  private final JsonSchema schema;

  private ControlPlaneSchemaValidator(JsonSchema schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  static ControlPlaneSchemaValidator create(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper");
    try {
      URL controlSchema = requireResource(CONTROL_SCHEMA);
      URL lifecycleSchema = requireResource(LIFECYCLE_SCHEMA);
      String controlSchemaId = requireSchemaId(mapper, controlSchema);
      String lifecycleSchemaId = requireSchemaId(mapper, lifecycleSchema);
      JsonSchemaFactory factory = JsonSchemaFactory.builder(
              JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
          .objectMapper(mapper)
          .addUriMappings(Map.of(
              controlSchemaId, controlSchema.toExternalForm(),
              lifecycleSchemaId, lifecycleSchema.toExternalForm()))
          .build();
      JsonSchema schema = factory.getSchema(controlSchema.toURI());
      return new ControlPlaneSchemaValidator(schema);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot load packaged canonical control-plane schema", exception);
    }
  }

  void validate(JsonNode node) {
    Set<ValidationMessage> errors = schema.validate(Objects.requireNonNull(node, "node"));
    if (!errors.isEmpty()) {
      throw new ControlPlaneContractException("Control-plane schema validation failed: " + errors);
    }
  }

  private static URL requireResource(String path) {
    URL resource = ControlPlaneSchemaValidator.class.getResource(path);
    if (resource == null) {
      throw new IllegalStateException("Missing packaged schema resource: " + path);
    }
    return resource;
  }

  private static String requireSchemaId(ObjectMapper mapper, URL resource) throws Exception {
    JsonNode id = mapper.readTree(resource).get("$id");
    if (id == null || !id.isTextual() || id.asText().isBlank()) {
      throw new IllegalStateException("Packaged schema has no non-blank $id: " + resource);
    }
    return id.asText();
  }
}
