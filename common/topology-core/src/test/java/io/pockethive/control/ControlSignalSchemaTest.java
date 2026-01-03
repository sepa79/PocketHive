package io.pockethive.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlSignalSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final JsonNode CONTROL_EVENTS_SCHEMA = loadControlEventsSchema();

    private static Set<String> collectProperties(JsonNode schema) {
        Set<String> properties = new LinkedHashSet<>();
        if (schema == null || schema.isMissingNode()) {
            return properties;
        }
        if (schema.has("$ref")) {
            properties.addAll(collectProperties(resolveSchema(schema.get("$ref").asText())));
        }
        if (schema.has("properties") && schema.get("properties").isObject()) {
            schema.get("properties").fieldNames().forEachRemaining(properties::add);
        }
        if (schema.has("allOf") && schema.get("allOf").isArray()) {
            schema.get("allOf").forEach(node -> properties.addAll(collectProperties(node)));
        }
        if (schema.has("oneOf") && schema.get("oneOf").isArray()) {
            schema.get("oneOf").forEach(node -> properties.addAll(collectProperties(node)));
        }
        return properties;
    }

    private static Set<String> collectRequired(JsonNode schema) {
        Set<String> required = new LinkedHashSet<>();
        if (schema == null || schema.isMissingNode()) {
            return required;
        }
        if (schema.has("$ref")) {
            required.addAll(collectRequired(resolveSchema(schema.get("$ref").asText())));
        }
        if (schema.has("required") && schema.get("required").isArray()) {
            schema.get("required").forEach(node -> required.add(node.asText()));
        }
        if (schema.has("allOf") && schema.get("allOf").isArray()) {
            schema.get("allOf").forEach(node -> required.addAll(collectRequired(node)));
        }
        if (schema.has("oneOf") && schema.get("oneOf").isArray()) {
            schema.get("oneOf").forEach(node -> required.addAll(collectRequired(node)));
        }
        return required;
    }

    @Test
    void schemaPropertiesMatchRecordComponents() throws Exception {
        JsonNode schema = resolveSchema("#/$defs/ControlSignal");

        Set<String> schemaProperties = collectProperties(schema);

        Set<String> recordProperties = Arrays.stream(ControlSignal.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(recordProperties, schemaProperties,
            "ControlSignal schema properties differ from record components");

        Set<String> required = collectRequired(schema);
        assertTrue(recordProperties.containsAll(required), "Required fields must exist on ControlSignal record");

        ControlSignal sample = ControlSignal.forInstance(
            "config-update",
            "sw-1",
            "generator",
            "inst-1",
            "orchestrator-1",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            Map.of("enabled", true)
        );
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(sample));
        Set<String> jsonProperties = new LinkedHashSet<>();
        json.fieldNames().forEachRemaining(jsonProperties::add);

        assertEquals(recordProperties, jsonProperties,
            "ControlSignal JSON properties must match record components");
    }

    @Test
    void noArgsSignalsStillEmitEmptyDataObject() throws Exception {
        ControlSignal signal = ControlSignal.forSwarm(
            "swarm-start",
            "sw-1",
            "orchestrator-1",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            null
        );
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(signal));
        assertTrue(json.has("data"));
        assertTrue(json.get("data").isObject());
        assertEquals(0, json.get("data").size());
    }

    private static JsonNode loadControlEventsSchema() {
        Path path = locateControlEventsSchema();
        try (InputStream in = Files.newInputStream(path)) {
            return MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load control-events schema from " + path, e);
        }
    }

    private static Path locateControlEventsSchema() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("docs/spec/control-events.schema.json");
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate docs/spec/control-events.schema.json starting from "
            + Paths.get("").toAbsolutePath());
    }

    private static JsonNode resolveSchema(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Schema ref must not be blank");
        }
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("Unsupported schema ref (expected local JSON pointer): " + ref);
        }
        JsonNode node = CONTROL_EVENTS_SCHEMA.at(ref.substring(1));
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("No control-events schema found for reference: " + ref);
        }
        return node;
    }
}
