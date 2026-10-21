package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PortableTemplateValidationTest extends ScenarioComponentTestFixture {
    @Test
    void invalidPortableResponseReturnsFindingsFromTheExistingBundleEndpoint() throws Exception {
        Files.writeString(capabilitiesDir.resolve("http-sequence-manifest.json"),
            Files.readString(capabilitiesDir.resolve("worker-manifest.json"))
                .replace("worker-image", "http-sequence").replace("\"worker\"", "\"http-sequence\""));
        capabilities.reload();
        var bundle = Files.createDirectories(scenariosDir.resolve("portable"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
            protocolVersion: "2.0.0"
            id: portable
            name: Portable
            template:
              image: ctrl-image:latest
              bees:
                - role: http-sequence
                  image: http-sequence:latest
                  work: {}
                  config:
                    inputs:
                      type: SCHEDULER
                      scheduler:
                        ratePerSec: 1
                        maxMessages: 0
                    outputs:
                      type: NONE
                    serviceId: accounts
                    templateRoot: %s/templates
                    baseUrl: https://sut.test/accounts
                    steps:
                      - callId: create
            """.formatted(ScenarioBundleLayout.CONTAINER_ROOT));
        var template = Files.createDirectories(bundle.resolve("templates")).resolve("account.json");
        try (var resource = getClass().getResourceAsStream("/portable/examples/account-json.json")) {
            assertThat(resource).isNotNull();
            Files.writeString(template, new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        scenarios.reload();
        var bundles = bundleService();
        var valid = bundles.validateExisting("portable");
        assertThat(valid.ok()).as("findings: %s", valid.findings()).isTrue();
        Files.writeString(template, Files.readString(template).replace("201", "201.0"));
        var authorization = mock(ScenarioManagerAuthorization.class);
        when(authorization.canManagePocketHive(null)).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new ScenarioValidationController(bundles, authorization)).build();
        mvc.perform(post("/validation/scenario-bundles/existing").param("bundleKey", "portable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.findings[?(@.code == 'TEMPLATE_INVALID' && @.path == 'templates/account.json')]").isNotEmpty());
    }
}
