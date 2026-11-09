package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.scenarios.validation.BundleValidationResult;
import io.pockethive.scenarios.validation.ValidationFinding;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ScenarioAuthProfileValidationTest extends ScenarioComponentTestFixture {
    private static final String SCENARIO_ID = "nested-auth";
    private static final String REQUESTS = "templates/requests";
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void selectedNearestProfileRetainsMissingUnknownAndOptionalVariableChecks() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, "api");
        writeAuthProfiles(bundle, "", "api", "root-token");
        writeAuthProfiles(bundle, "templates", "api", "{{ eval('vars.nestedToken') }}");

        assertThat(finding(validate(), "VARIABLES_MISSING").message()).contains("vars.nestedToken");
        writeVariables(bundle, "other");
        ValidationFinding unknown = finding(validate(), "VARIABLE_REFERENCE_UNKNOWN");
        assertThat(unknown.path()).isEqualTo("templates/authProfiles.yaml:profiles.api");
        assertThat(unknown.message()).contains("vars.nestedToken");

        writeVariables(bundle, "nestedToken");
        assertAvailable();
        assertThat(new ScenarioVariablesService(scenarios, validator)
            .resolve(SCENARIO_ID, "default", null).vars()).isEmpty();
    }

    @Test
    void nearestProfileDoesNotRequireABundleRootProfileDocument() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, "api");
        writeAuthProfiles(bundle, "templates", "api", "nested-token");

        assertAvailable();
    }

    @Test
    void nearestDocumentDoesNotMergeProfileIdsFromItsAncestor() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, "api");
        writeAuthProfiles(bundle, "", "api", "root-token");
        writeAuthProfiles(bundle, "templates", "other", "nested-token");

        ValidationFinding missing = finding(validate(), "AUTH_REF_PROFILE_MISSING");
        assertThat(missing.path()).isEqualTo("templates/requests/call.yaml:authRef.profileId");
        assertThat(missing.message()).contains("api");
    }

    @Test
    void malformedNearestDocumentFailsWithoutRetryingAValidAncestor() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, "api");
        writeAuthProfiles(bundle, "", "api", "root-token");
        Files.writeString(bundle.resolve("templates/authProfiles.yaml"), "profiles: [unterminated");

        assertThat(finding(validate(), "AUTH_PROFILES_INVALID").path())
            .isEqualTo("templates/authProfiles.yaml");
    }

    @Test
    void sameProfileIdInTwoConfiguredRootsKeepsItsOwnSourcesAndMembership() throws Exception {
        Path bundle = createBundle(containerRoot("first/requests"), containerRoot("second/requests"));
        writeRequest(bundle, "first/requests", "api");
        writeRequest(bundle, "second/requests", "api");
        writeAuthProfiles(bundle, "first", "api", "{{ eval('vars.firstToken') }}");
        writeAuthProfiles(bundle, "second", "api", "{{ eval('vars.secondToken') }}");
        writeVariables(bundle, "firstToken");

        ValidationFinding unknown = finding(validate(), "VARIABLE_REFERENCE_UNKNOWN");
        assertThat(unknown.path()).isEqualTo("second/authProfiles.yaml:profiles.api");
        assertThat(unknown.message()).contains("vars.secondToken");
        writeVariables(bundle, "firstToken", "secondToken");
        assertAvailable();

        writeAuthProfiles(bundle, "second", "other", "second-token");
        assertThat(finding(validate(), "AUTH_REF_PROFILE_MISSING").path())
            .isEqualTo("second/requests/call.yaml:authRef.profileId");
    }

    @Test
    void discoveryStartsAtTheConfiguredRootRatherThanTheRequestFileDirectory() throws Exception {
        Path bundle = createBundle(containerRoot("templates"));
        writeRequest(bundle, "templates/requests/deep", "api");
        writeAuthProfiles(bundle, "templates", "api", "selected-token");
        writeAuthProfiles(bundle, "templates/requests", "api", "{{ eval('vars.notSelected') }}");

        assertAvailable();
    }

    @Test
    void unusedSiblingAuthDocumentsRemainOpaque() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, "api");
        writeAuthProfiles(bundle, "", "api", "root-token");
        writeAuthProfiles(bundle, "templates", "api", "selected-token");
        writeAuthProfiles(bundle, "unused", "api", "{{ eval('vars.unusedToken') }}");
        writeAuthProfiles(bundle, "unused/broken", "api", "{{ broken");

        assertAvailable();
    }

    @Test
    void ymlIsNotASubstituteForTheCanonicalAuthProfileFilename() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, "api");
        Path profiles = writeAuthProfiles(bundle, "templates", "api", "nested-token");
        Files.move(profiles, profiles.resolveSibling("authProfiles.yml"));

        finding(validate(), "AUTH_PROFILES_MISSING");
    }

    @Test
    void bundleRootAuthContractIsValidatedEvenWithoutAuthReferences() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, null);
        Files.writeString(bundle.resolve("authProfiles.yaml"), "profiles: []\n");

        assertThat(finding(validate(), "AUTH_PROFILES_INVALID").path())
            .isEqualTo("authProfiles.yaml:profiles");
    }

    @Test
    void selectedAuthSourcesRejectReservedVarsDeclarations() throws Exception {
        Path bundle = createBundle(containerRoot(REQUESTS));
        writeRequest(bundle, REQUESTS, "api");
        writeAuthProfiles(bundle, "", "api", "root-token");
        writeAuthProfiles(bundle, "templates", "api", "{% set vars = 'shadow' %}token");

        ValidationFinding invalid = finding(validate(), "TEMPLATE_INVALID");
        assertThat(invalid.path()).isEqualTo("templates/authProfiles.yaml:profiles.api");
        assertThat(invalid.message()).contains("vars", "reserved");
    }

    @Test
    void symbolicRootsDeferWithoutReadingConditionalAuthDocuments() throws Exception {
        Path bundle = createBundle("{{ vars.resourceRoot }}");
        writeRequest(bundle, REQUESTS, "api");
        writeAuthProfiles(bundle, "templates", "api", "{{ eval('vars.notSelected') }}");
        writeVariables(bundle, "resourceRoot");

        BundleValidationResult result = validate();
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(deferred -> {
            assertThat(deferred.code()).isEqualTo("WORK_CONFIGURATION_DEFERRED");
            assertThat(deferred.path()).endsWith(".config.templateRoot");
        });
    }

    @Test
    void externalRootsDoNotInspectHostAuthDocuments() throws Exception {
        Path external = Files.createDirectories(tempDir.resolve("external"));
        Path externalRequests = Files.createDirectories(external.resolve("requests"));
        createBundle(externalRequests.toString());
        writeRequest(external, "requests", "api");
        Files.writeString(external.resolve("authProfiles.yaml"), "profiles: [unterminated");

        assertAvailable();
    }

    private Path createBundle(String... roots) throws Exception {
        Files.writeString(capabilitiesDir.resolve("request-builder-manifest.json"), """
            {
              "schemaVersion": "1.0",
              "capabilitiesVersion": "1.0",
              "role": "request-builder",
              "image": {"name": "request-builder", "tag": "latest"}
            }
            """);
        capabilities.reload();
        writeBundleScenario(SCENARIO_ID);
        Path bundle = scenariosDir.resolve(SCENARIO_ID);
        Path descriptor = bundle.resolve(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE);
        ObjectNode scenario = (ObjectNode) yaml.readTree(Files.readString(descriptor));
        var bees = ((ObjectNode) scenario.get("template")).putArray("bees");
        for (int index = 0; index < roots.length; index++) {
            ObjectNode bee = bees.addObject();
            bee.put("role", "builder-" + index).put("image", "request-builder:latest");
            bee.putObject("work");
            ObjectNode config = bee.putObject("config");
            config.putObject("inputs").put("type", "RABBITMQ");
            config.putObject("outputs").put("type", "NONE");
            config.put("templateRoot", roots[index]).put("serviceId", "accounts")
                .put("passThroughOnMissingTemplate", false);
        }
        Files.writeString(descriptor, yaml.writeValueAsString(scenario));
        return bundle;
    }

    private void writeRequest(Path bundle, String directory, String profileId) throws Exception {
        ObjectNode request = yaml.createObjectNode();
        request.put("protocol", "HTTP").put("serviceId", "accounts").put("callId", "create")
            .put("method", "POST").put("pathTemplate", "/test").put("bodyTemplate", "{}");
        if (profileId != null)
            request.putObject("authRef").put("profileId", profileId).put("applyAs", "HTTP_AUTHORIZATION_BEARER");
        Files.writeString(Files.createDirectories(bundle.resolve(directory)).resolve("call.yaml"),
            yaml.writeValueAsString(request));
    }

    private Path writeAuthProfiles(Path bundle, String directory, String profileId, String token) throws Exception {
        Path profiles = Files.createDirectories(bundle.resolve(directory)).resolve("authProfiles.yaml");
        ObjectNode document = yaml.createObjectNode();
        ObjectNode profile = document.putObject("profiles").putObject(profileId);
        profile.put("type", "STATIC_TOKEN");
        profile.putObject("storage").put("mode", "NONE");
        profile.put("token", token);
        Files.writeString(profiles, yaml.writeValueAsString(document));
        return profiles;
    }

    private void writeVariables(Path bundle, String... names) throws Exception {
        ObjectNode document = yaml.createObjectNode().put("version", 1);
        document.putArray("profiles").addObject().put("id", "default").put("name", "Default");
        var definitions = document.putArray("definitions");
        for (String name : names)
            definitions.addObject().put("name", name).put("type", "STRING").put("scope", "GLOBAL")
                .put("required", false);
        Files.writeString(bundle.resolve("variables.yaml"), yaml.writeValueAsString(document));
    }

    private BundleValidationResult validate() throws Exception {
        scenarios.reload();
        return bundleService().validateExisting(SCENARIO_ID);
    }

    private ValidationFinding finding(BundleValidationResult result, String code) {
        assertThat(result.ok()).as("findings: %s", result.findings()).isFalse();
        var selected = result.findings().stream().filter(item -> item.code().equals(code)).toList();
        assertThat(selected).as("%s findings: %s", code, result.findings()).hasSize(1);
        return selected.getFirst();
    }

    private void assertAvailable() throws Exception {
        BundleValidationResult result = validate();
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        assertThat(result.findings()).isEmpty();
        assertThat(scenarios.listAvailableSummaries()).extracting(ScenarioSummary::id).containsExactly(SCENARIO_ID);
    }

    private String containerRoot(String directory) {
        return ScenarioBundleLayout.CONTAINER_ROOT + "/" + directory;
    }
}
