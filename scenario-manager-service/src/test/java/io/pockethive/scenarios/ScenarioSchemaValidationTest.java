package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioSchemaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream schemaStream = Objects.requireNonNull(
                ScenarioSchemaValidationTest.class.getResourceAsStream("/scenario-schema.json"),
                "scenario-schema.json missing from classpath")) {
            JsonNode schemaNode = MAPPER.readTree(schemaStream);
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        }
    }

    @Test
    void createRequestMatchesSchema() throws IOException {
        JsonNode scenario = MAPPER.readTree("""
            {
              "id": "scenario-1",
              "name": "Scenario 1",
              "assets": {
                "suts": [
                  {"id": "sut-1", "name": "SUT", "entrypoint": "/run.sh", "version": "1.0"}
                ],
                "datasets": [
                  {"id": "dataset-1", "name": "Dataset", "uri": "s3://bucket/data.json", "format": "json"}
                ],
                "swarmTemplates": [
                  {"id": "template-1", "name": "Template", "sutId": "sut-1", "datasetId": "dataset-1", "swarmSize": 1}
                ]
              },
              "template": {"image": "controller", "bees": []},
              "tracks": []
            }
            """);

        Set<ValidationMessage> violations = schema.validate(scenario);
        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsBlankIdentifiers() throws IOException {
        JsonNode scenario = MAPPER.readTree("""
            {
              "id": "",
              "name": "",
              "assets": {
                "suts": [
                  {"id": "", "name": "", "entrypoint": "", "version": ""}
                ],
                "datasets": [
                  {"id": "", "name": "", "uri": "", "format": ""}
                ],
                "swarmTemplates": [
                  {"id": "", "name": "", "sutId": "", "datasetId": "", "swarmSize": 0}
                ]
              }
            }
            """);

        Set<ValidationMessage> violations = schema.validate(scenario);
        assertThat(violations).isNotEmpty();
    }
}
