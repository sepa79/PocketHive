package io.pockethive.scenarios.capabilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates worker capability manifests against the canonical JSON schema bundled with the service.
 */
@Component
public class CapabilitiesManifestValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_CLASSPATH = "contracts/capabilities/manifest.schema.json";
    private static final Pattern SCHEMA_VERSION_PATTERN = Pattern.compile("schemaVersion=([\\w.-]+)");

    private final JsonSchema schema;
    private final JsonNode schemaNode;

    public CapabilitiesManifestValidator() {
        try {
            byte[] schemaBytes = readSchemaBytes();
            this.schemaNode = MAPPER.readTree(schemaBytes);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            this.schema = factory.getSchema(new ByteArrayInputStream(schemaBytes));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialise capabilities manifest schema", e);
        }
    }

    /**
     * Validate the given manifest against the schema and return any violations.
     */
    public Set<ValidationMessage> validate(JsonNode manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("Manifest payload must not be null");
        }
        return schema.validate(manifest);
    }

    /**
     * Validate the manifest and raise an {@link IllegalArgumentException} if violations are detected.
     */
    public void assertValid(JsonNode manifest) {
        Set<ValidationMessage> violations = validate(manifest);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Invalid capabilities manifest: " + violations);
        }
    }

    /**
     * Expose the schema node for callers that need metadata such as the schema version.
     */
    public JsonNode schemaNode() {
        return schemaNode.deepCopy();
    }

    public String schemaVersion() {
        JsonNode commentNode = schemaNode.path("$comment");
        if (commentNode.isTextual()) {
            Matcher matcher = SCHEMA_VERSION_PATTERN.matcher(commentNode.asText());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return schemaNode.path("schemaVersion").asText();
    }

    private byte[] readSchemaBytes() throws IOException {
        ClassPathResource resource = new ClassPathResource(SCHEMA_CLASSPATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return inputStream.readAllBytes();
        }
    }
}
