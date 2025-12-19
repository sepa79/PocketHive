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
import java.util.Set;

public final class ControlEventsSchemaValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final JsonSchema SCHEMA = loadSchema();

  private ControlEventsSchemaValidator() {
  }

  public static void assertValid(String json) throws IOException {
    JsonNode node = MAPPER.readTree(json);
    assertValid(node);
  }

  public static void assertValid(JsonNode node) {
    Set<ValidationMessage> errors = SCHEMA.validate(node);
    assertThat(errors).isEmpty();
  }

  private static JsonSchema loadSchema() {
    Path schemaPath = locateRepoSchema();
    try {
      JsonNode schemaNode = MAPPER.readTree(schemaPath.toFile());
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      return factory.getSchema(schemaNode);
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
