package io.pockethive.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.asyncapi.AsyncApiSchemaValidator;
import org.junit.jupiter.api.Test;

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

    private static final AsyncApiSchemaValidator VALIDATOR = AsyncApiSchemaValidator.loadDefault();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static Set<String> collectProperties(JsonNode schema) {
        Set<String> properties = new LinkedHashSet<>();
        if (schema == null || schema.isMissingNode()) {
            return properties;
        }
        if (schema.has("$ref")) {
            properties.addAll(collectProperties(VALIDATOR.schema(schema.get("$ref").asText())));
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
            required.addAll(collectRequired(VALIDATOR.schema(schema.get("$ref").asText())));
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
        JsonNode schema = VALIDATOR.schema("#/components/schemas/ControlSignalPayload");

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
            Map.of("data", Map.of("enabled", true))
        );
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(sample));
        Set<String> jsonProperties = new LinkedHashSet<>();
        json.fieldNames().forEachRemaining(jsonProperties::add);

        assertEquals(recordProperties, jsonProperties,
            "ControlSignal JSON properties must match record components");
    }
}
