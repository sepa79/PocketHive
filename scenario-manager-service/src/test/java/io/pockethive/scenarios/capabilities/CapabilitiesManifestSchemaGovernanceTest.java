package io.pockethive.scenarios.capabilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilitiesManifestSchemaGovernanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXPECTED_SCHEMA_VERSION = "1.0.0";
    private static final Pattern SCHEMA_VERSION_PATTERN = Pattern.compile("schemaVersion=([\\w.-]+)");

    @Test
    void schemaVersionMustBeExplicitlyBumped() throws IOException {
        JsonNode docsSchema = readSchema(docsSchemaPath());
        JsonNode packagedSchema = readSchema(packagedSchemaPath());

        String docsVersion = parseSchemaVersion(docsSchema);
        assertEquals(EXPECTED_SCHEMA_VERSION, docsVersion,
                () -> "Update EXPECTED_SCHEMA_VERSION when manifest.schema.json changes");

        String packagedVersion = parseSchemaVersion(packagedSchema);
        assertEquals(EXPECTED_SCHEMA_VERSION, packagedVersion,
                () -> "Update packaged schema version metadata when manifest.schema.json changes");

        assertEquals(docsSchema, packagedSchema, "Packaged schema must match documentation copy exactly");

        CapabilitiesManifestValidator validator = new CapabilitiesManifestValidator();
        assertEquals(EXPECTED_SCHEMA_VERSION, validator.schemaVersion(),
                "Validator must expose the same schema version metadata");
    }

    private static JsonNode readSchema(Path path) throws IOException {
        assertTrue(Files.exists(path), () -> "Missing schema file: " + path);
        return MAPPER.readTree(Files.readString(path));
    }

    private static String parseSchemaVersion(JsonNode schema) {
        JsonNode commentNode = schema.path("$comment");
        if (commentNode.isTextual()) {
            Matcher matcher = SCHEMA_VERSION_PATTERN.matcher(commentNode.asText());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return schema.path("schemaVersion").asText();
    }

    private static Path docsSchemaPath() {
        return Path.of("..", "docs", "pockethive_scenario_builder_mvp", "contracts", "capabilities", "manifest.schema.json").normalize();
    }

    private static Path packagedSchemaPath() {
        return Path.of("src", "main", "resources", "contracts", "capabilities", "manifest.schema.json");
    }
}
