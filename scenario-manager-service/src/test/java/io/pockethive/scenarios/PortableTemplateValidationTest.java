package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PortableTemplateValidationTest extends ScenarioComponentTestFixture {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sharedPortableCallIsAvailableAndAcceptedByTheExistingBundleEndpoint() throws Exception {
        createPortableBundle();
        assertAccepted();
    }

    @Test
    void invalidPortableResponseReturnsFindingsFromTheExistingBundleEndpoint() throws Exception {
        Path template = createPortableBundle();
        Files.writeString(template, Files.readString(template).replace("201", "201.0"));
        assertRejected("templates/account.json");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "bodyTemplate|{{ eval('1 + 1') }}",
        "pathTemplate|{{ eval('1 + 1') }}",
        "headersTemplate|{{ eval('1 + 1') }}",
        "bodyTemplate|{{ xml_path(record, '//id', {}) }}",
        "bodyTemplate|{{ payload.customerId }}"
    })
    void rejectsNativeOrUndeclaredSyntaxInEveryPortableTemplatePosition(String field, String source) throws Exception {
        Path template = createPortableBundle();
        ObjectNode document = (ObjectNode) json.readTree(Files.readString(template));
        ObjectNode request = (ObjectNode) document.get("request");
        if (field.equals("headersTemplate")) {
            ((ObjectNode) request.get(field)).put("X-Invalid", source);
        } else {
            request.put(field, source);
        }
        Files.writeString(template, json.writeValueAsString(document));
        assertRejected("templates/account.json");
    }

    @Test
    void declaredPortableFunctionsMustRemainInTheSelectedCapabilityProfile() throws Exception {
        Path template = createPortableBundle();
        ObjectNode document = (ObjectNode) json.readTree(Files.readString(template));
        document.putArray("requires").add("json_path");
        Files.writeString(template, json.writeValueAsString(document));
        assertRejected("templates/account.json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry: {maxAttempts: 2}", "continueOnNon2xx: true",
        "extracts: [{fromJsonPointer: /accountNumber, to: accountNumber}]"})
    void rejectsRuntimeIncompatibleOptionsOnTheSelectedPortableStep(String settings) throws Exception {
        createPortableBundle();
        Path scenario = scenariosDir.resolve("portable/scenario.yaml");
        Files.writeString(scenario, Files.readString(scenario)
            .replace("- callId: create", "- callId: create\n            " + settings));
        assertRejected("scenario.yaml:template.bees.http-sequence.config.steps[0]");
    }

    @Test
    void nativeEvalAndRetryRemainValidOnTheNativePath() throws Exception {
        Path template = createPortableBundle();
        ObjectNode portable = (ObjectNode) json.readTree(Files.readString(template));
        ObjectNode nativeRequest = (ObjectNode) portable.get("request");
        nativeRequest.put("bodyTemplate", "{{ eval('1 + 1') }}");
        Files.writeString(template, json.writeValueAsString(nativeRequest));
        Path scenario = scenariosDir.resolve("portable/scenario.yaml");
        Files.writeString(scenario, Files.readString(scenario)
            .replace("- callId: create", "- callId: create\n            retry: {maxAttempts: 2}"));
        assertAccepted();
    }

    @Test
    void requestBuilderUsesItsRootServiceIdToRejectTheReferencedPortableCall() throws Exception {
        createPortableBundle();
        configureRequestBuilder("create");
        assertRejected("templates/account.json");
    }

    @Test
    void requestBuilderCanShareATemplateRootWhenItOnlyReferencesANativeCall() throws Exception {
        Path template = createPortableBundle();
        ObjectNode portable = (ObjectNode) json.readTree(Files.readString(template));
        ObjectNode nativeRequest = (ObjectNode) portable.get("request");
        nativeRequest.put("callId", "native");
        nativeRequest.put("bodyTemplate", "{{ eval('1 + 1') }}");
        Files.writeString(template.resolveSibling("native.json"), json.writeValueAsString(nativeRequest));
        configureRequestBuilder("native");
        assertAccepted();
    }

    private void configureRequestBuilder(String callId) throws Exception {
        for (String image : new String[] {"request-builder", "generator"}) {
            Files.writeString(capabilitiesDir.resolve(image + "-manifest.json"),
                Files.readString(capabilitiesDir.resolve("worker-manifest.json"))
                    .replace("worker-image", image).replace("\"worker\"", "\"" + image + "\""));
        }
        capabilities.reload();
        Path scenario = scenariosDir.resolve("portable/scenario.yaml");
        String source = Files.readString(scenario)
            .replace("image: http-sequence:latest", "image: request-builder:latest")
            .replace("        steps:\n          - callId: create\n", "")
            .replace("      work: {}", "      work:\n        in:\n          in: build")
            .replace("        inputs:\n          type: SCHEDULER\n          scheduler:\n            ratePerSec: 1\n            maxMessages: 0",
                "        inputs:\n          type: RABBITMQ");
        String producer = """
              bees:
                - role: generator
                  image: generator:latest
                  work:
                    out:
                      out: build
                  config:
                    inputs:
                      type: SCHEDULER
                      scheduler:
                        ratePerSec: 1
                        maxMessages: 0
                    outputs:
                      type: RABBITMQ
                    message:
                      headers:
                        x-ph-call-id: %s
            """.formatted(callId);
        Files.writeString(scenario, source.replace("  bees:\n", producer));
    }

    private Path createPortableBundle() throws Exception {
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
        Path template = Files.createDirectories(bundle.resolve("templates")).resolve("account.json");
        try (var resource = getClass().getResourceAsStream("/portable/examples/account-json.json")) {
            assertThat(resource).isNotNull();
            Files.writeString(template, new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertAccepted();
        return template;
    }

    private void assertAccepted() throws Exception {
        scenarios.reload();
        var result = bundleService().validateExisting("portable");
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        mvc().perform(post("/validation/scenario-bundles/existing").param("bundleKey", "portable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        scenarios.reload();
        assertThat(scenarios.listAvailableSummaries()).extracting(ScenarioSummary::id).containsExactly("portable");
    }

    private void assertRejected(String path) throws Exception {
        scenarios.reload();
        var result = bundleService().validateExisting("portable");
        assertThat(result.ok()).as("findings: %s", result.findings()).isFalse();
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TEMPLATE_INVALID");
            assertThat(finding.path()).isEqualTo(path);
        });
        mvc().perform(post("/validation/scenario-bundles/existing").param("bundleKey", "portable"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.findings[?(@.code == 'TEMPLATE_INVALID')]").isNotEmpty());
        scenarios.reload();
        assertThat(scenarios.listAvailableSummaries()).isEmpty();
        assertThat(scenarios.listDefunctSummaries()).extracting(ScenarioSummary::id).containsExactly("portable");
    }

    private MockMvc mvc() {
        var authorization = mock(ScenarioManagerAuthorization.class);
        when(authorization.canManagePocketHive(null)).thenReturn(true);
        return MockMvcBuilders.standaloneSetup(new ScenarioValidationController(bundleService(), authorization)).build();
    }
}
