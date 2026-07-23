package io.pockethive.controlplane.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Iterator;
import java.util.Map;

public final class ControlEventsSchemaValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final JsonSchema SCHEMA = loadSchema();

  private ControlEventsSchemaValidator() {
  }

  public static void assertValid(String json) throws IOException {
    assertValid(validate(json));
  }

  public static void assertValid(JsonNode node) {
    assertValid(validate(node));
  }

  public static Set<ValidationMessage> validate(String json) throws IOException {
    JsonNode node = MAPPER.readTree(json);
    return validate(node);
  }

  public static Set<ValidationMessage> validate(JsonNode node) {
    Set<ValidationMessage> errors = SCHEMA.validate(node);
    return errors;
  }

  private static void assertValid(Set<ValidationMessage> errors) {
    assertThat(errors).isEmpty();
  }

  private static JsonSchema loadSchema() {
    Path schemaPath = locateRepoSchema();
    try {
      JsonNode schemaNode = MAPPER.readTree(schemaPath.toFile());
      inlineLifecycleDefinitions((ObjectNode) schemaNode, schemaPath.resolveSibling("swarm-lifecycle.schema.json"));
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      return factory.getSchema(schemaNode);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load schema from " + schemaPath, e);
    }
  }

  private static void inlineLifecycleDefinitions(ObjectNode controlSchema, Path lifecyclePath) throws IOException {
    JsonNode lifecycleSchema = MAPPER.readTree(lifecyclePath.toFile());
    ObjectNode controlDefinitions = (ObjectNode) controlSchema.required("$defs");
    lifecycleSchema.required("$defs").fields().forEachRemaining(entry -> {
      JsonNode definition = entry.getValue().deepCopy();
      prefixLocalLifecycleReferences(definition);
      controlDefinitions.set("Lifecycle" + entry.getKey(), definition);
    });
    rewriteLifecycleReferences(controlSchema);
  }

  private static void prefixLocalLifecycleReferences(JsonNode node) {
    if (node instanceof ObjectNode object) {
      JsonNode reference = object.get("$ref");
      if (reference != null && reference.isTextual()
          && reference.asText().startsWith("#/$defs/")) {
        object.put("$ref", reference.asText().replace("#/$defs/", "#/$defs/Lifecycle"));
      }
      object.fields().forEachRemaining(entry -> prefixLocalLifecycleReferences(entry.getValue()));
      return;
    }
    if (node.isArray()) {
      node.forEach(ControlEventsSchemaValidator::prefixLocalLifecycleReferences);
    }
  }

  private static void rewriteLifecycleReferences(JsonNode node) {
    if (node instanceof ObjectNode object) {
      JsonNode reference = object.get("$ref");
      if (reference != null && reference.isTextual()
          && reference.asText().startsWith("swarm-lifecycle.schema.json#/$defs/")) {
        object.put("$ref", reference.asText().replace(
            "swarm-lifecycle.schema.json#/$defs/", "#/$defs/Lifecycle"));
      }
      Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
      while (fields.hasNext()) {
        rewriteLifecycleReferences(fields.next().getValue());
      }
      return;
    }
    if (node.isArray()) {
      node.forEach(ControlEventsSchemaValidator::rewriteLifecycleReferences);
    }
  }

  private static Path locateRepoSchema() {
    String root = System.getProperty("maven.multiModuleProjectDirectory");
    Path base = (root != null && !root.isBlank())
        ? Path.of(root)
        : Path.of("").toAbsolutePath();
    for (int i = 0; i < 10 && base != null; i++) {
      Path candidate = base.resolve("docs").resolve("spec").resolve("control-events.schema.json");
      if (Files.exists(candidate)) {
        return candidate;
      }
      base = base.getParent();
    }
    throw new IllegalStateException("Unable to locate docs/spec/control-events.schema.json from current directory");
  }
}
