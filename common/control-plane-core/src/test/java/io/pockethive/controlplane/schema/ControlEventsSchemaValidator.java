package io.pockethive.controlplane.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class ControlEventsSchemaValidator {

  private static final String CONTROL_SCHEMA_ID =
      "https://pockethive.dev/docs/spec/control-events.schema.json";
  private static final String LIFECYCLE_SCHEMA_ID =
      "https://pockethive.dev/docs/spec/swarm-lifecycle.schema.json";
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
      Path lifecyclePath = schemaPath.resolveSibling("swarm-lifecycle.schema.json");
      JsonSchemaFactory factory = JsonSchemaFactory.builder(
              JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
          .addUriMappings(Map.of(
              CONTROL_SCHEMA_ID, schemaPath.toUri().toString(),
              LIFECYCLE_SCHEMA_ID, lifecyclePath.toUri().toString()))
          .build();
      return factory.getSchema(schemaPath.toUri());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load schema from " + schemaPath, e);
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
