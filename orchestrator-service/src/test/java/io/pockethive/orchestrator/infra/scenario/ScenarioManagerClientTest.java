package io.pockethive.orchestrator.infra.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ScenarioManagerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void scenarioTemplateResponseIgnoresAdditionalTemplateFields() throws Exception {
        String payload = """
            {
              "bundleKey": "e2e/local-rest",
              "bundlePath": "e2e/local-rest",
              "folderPath": "e2e",
              "id": "local-rest",
              "name": "Local REST",
              "description": "demo",
              "controllerImage": "swarm-controller:latest",
              "bees": [],
              "defunct": false,
              "defunctReason": null
            }
            """;

        ScenarioManagerClient.ScenarioTemplateResponse response =
            objectMapper.readValue(payload, ScenarioManagerClient.ScenarioTemplateResponse.class);

        assertThat(response.id()).isEqualTo("local-rest");
        assertThat(response.bundlePath()).isEqualTo("e2e/local-rest");
        assertThat(response.folderPath()).isEqualTo("e2e");
        assertThat(response.defunct()).isFalse();
    }
}
