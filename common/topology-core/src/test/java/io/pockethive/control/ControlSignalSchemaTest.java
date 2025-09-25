package io.pockethive.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.asyncapi.AsyncApiSchemaValidator;
import io.pockethive.control.CommandTarget;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void schemaPropertiesMatchRecordComponents() throws Exception {
        JsonNode schema = VALIDATOR.schema("#/components/schemas/ControlSignalPayload");

        Set<String> schemaProperties = new LinkedHashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(schemaProperties::add);

        Set<String> recordProperties = Arrays.stream(ControlSignal.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(recordProperties, schemaProperties,
            "ControlSignal schema properties differ from record components");

        Set<String> required = new LinkedHashSet<>();
        if (schema.has("required") && schema.get("required").isArray()) {
            schema.get("required").forEach(node -> required.add(node.asText()));
        }
        assertTrue(recordProperties.containsAll(required), "Required fields must exist on ControlSignal record");

        ControlSignal sample = ControlSignal.forInstance(
            "config-update",
            "sw-1",
            "generator",
            "inst-1",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
            Map.of("data", Map.of("enabled", true))
        );
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(sample));
        Set<String> jsonProperties = new LinkedHashSet<>();
        json.fieldNames().forEachRemaining(jsonProperties::add);

        assertEquals(recordProperties, jsonProperties,
            "ControlSignal JSON properties must match record components");
    }
}
