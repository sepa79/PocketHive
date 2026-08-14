package io.pockethive.worker.sdk.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.net.URL;
import java.util.Objects;
import java.util.Set;

/** Schema component of the canonical WorkItem JSON boundary. */
final class WorkItemSchemaValidator {

    private static final String SCHEMA_RESOURCE =
        "/io/pockethive/worker/sdk/schema/workitem-envelope.schema.json";

    private final JsonSchema schema;

    private WorkItemSchemaValidator(JsonSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    static WorkItemSchemaValidator create(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        try {
            URL resource = WorkItemSchemaValidator.class.getResource(SCHEMA_RESOURCE);
            if (resource == null) {
                throw new IllegalStateException("Missing packaged canonical WorkItem schema: " + SCHEMA_RESOURCE);
            }
            JsonSchemaFactory factory = JsonSchemaFactory.builder(
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                .objectMapper(mapper)
                .build();
            return new WorkItemSchemaValidator(factory.getSchema(resource.toURI()));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load packaged canonical WorkItem schema", exception);
        }
    }

    void validate(JsonNode node) {
        Set<ValidationMessage> errors = schema.validate(Objects.requireNonNull(node, "node"));
        if (!errors.isEmpty()) {
            throw new WorkItemContractException("WorkItem schema validation failed: " + errors);
        }
    }
}
