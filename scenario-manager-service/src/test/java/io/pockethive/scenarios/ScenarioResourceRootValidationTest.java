package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.swarm.model.ScenarioConfigurationPreparation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class ScenarioResourceRootValidationTest extends ScenarioComponentTestFixture {
    private final com.fasterxml.jackson.databind.ObjectMapper yaml = new com.fasterxml.jackson.databind.ObjectMapper(
        new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    @ParameterizedTest
    @CsvSource({"request-builder,false", "request-builder,true", "db-query,false", "db-query,true",
        "clearing-export,false", "clearing-export,true"})
    void bootstrapRootsDeferOnlyTheirSelectedDocuments(String workerRole, boolean embedded) throws Exception {
        boolean clearing = workerRole.equals("clearing-export");
        String field = clearing ? "schemaRegistryRoot" : "templateRoot";
        String directory = clearing ? "clearing-schemas" : "templates";
        String concreteRoot = ScenarioBundleLayout.CONTAINER_ROOT + "/" + directory;
        String location = embedded ? ScenarioBundleLayout.CONTAINER_ROOT + "/{{ vars.directory }}" : "{{ vars.resourceRoot }}";
        ObjectNode scenario = createScenario(workerRole, field, location);
        Path bundle = scenariosDir.resolve("roots");
        writeSelectedDocument(workerRole, bundle);
        String name = embedded ? "directory" : "resourceRoot";
        String value = embedded ? directory : concreteRoot;
        Files.writeString(bundle.resolve("variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: %s, type: STRING, scope: GLOBAL, required: true}]
            values: {global: {default: {%s: '%s'}}}
            """.formatted(name, name, value));

        scenarios.reload();
        var result = bundleService().validateExisting("roots");
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("WORK_CONFIGURATION_DEFERRED");
            assertThat(finding.severity().name()).isEqualTo("WARNING");
            assertThat(finding.path()).isEqualTo("scenario.yaml:template.bees.target.config." + field);
        });
        var vars = new ScenarioVariablesService(scenarios, validator).resolve("roots", "default", null).vars();
        assertThat(ScenarioConfigurationPreparation.applyVariables(Map.of(field, location), vars))
            .containsEntry(field, concreteRoot);

        ObjectNode config = (ObjectNode) scenario.at("/template/bees/0/config");
        config.putObject("interceptors").putObject("templating").put("template", "{{ eval('vars.neighbor') }}");
        writeScenario(scenario);
        scenarios.reload();
        var neighbor = bundleService().validateExisting("roots");
        assertThat(neighbor.findings()).extracting(finding -> finding.code()).contains("VARIABLE_REFERENCE_UNKNOWN");
        assertThat(neighbor.findings()).filteredOn(finding -> finding.code().equals("WORK_CONFIGURATION_DEFERRED")).hasSize(1);

        config.remove("interceptors");
        config.put(field, concreteRoot);
        writeScenario(scenario);
        scenarios.reload();
        var concrete = bundleService().validateExisting("roots");
        assertThat(concrete.ok()).isFalse();
        assertThat(concrete.findings()).extracting(finding -> finding.code())
            .contains(workerRole.equals("request-builder") ? "TEMPLATE_INVALID" : "VARIABLE_REFERENCE_UNKNOWN")
            .doesNotContain("WORK_CONFIGURATION_DEFERRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-builder", "db-query", "clearing-export"})
    void concreteExternalRootsKeepTheirExistingPolicy(String workerRole) throws Exception {
        createScenario(workerRole, workerRole.equals("clearing-export") ? "schemaRegistryRoot" : "templateRoot", "/opt/shared/templates");
        scenarios.reload();
        var result = bundleService().validateExisting("roots");
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-builder", "db-query", "clearing-export"})
    void containedDotPrefixedDirectoriesRemainValidated(String workerRole) throws Exception {
        boolean clearing = workerRole.equals("clearing-export");
        String directory = clearing ? "clearing-schemas" : "templates";
        createScenario(workerRole, clearing ? "schemaRegistryRoot" : "templateRoot",
            ScenarioBundleLayout.CONTAINER_ROOT + "/.." + directory);
        Path bundle = scenariosDir.resolve("roots");
        writeSelectedDocument(workerRole, bundle, false);
        Path selectedRoot = Files.move(bundle.resolve(directory), bundle.resolve(".." + directory));
        scenarios.reload();
        var valid = bundleService().validateExisting("roots");
        assertThat(valid.ok()).as("findings: %s", valid.findings()).isTrue();
        assertThat(valid.findings()).isEmpty();

        String relativeFile = clearing ? "pcs-clearing/1.0.0/schema.yaml"
            : workerRole.equals("db-query") ? "query.yaml" : "call.yaml";
        Path selectedFile = selectedRoot.resolve(relativeFile);
        ObjectNode document = (ObjectNode) yaml.readTree(Files.readString(selectedFile));
        if (clearing) ((ObjectNode) document.at("/recordMapping/payload")).put("expression", "{{ eval('vars.missing') }}");
        else if (workerRole.equals("db-query")) ((ObjectNode) document.at("/params/0")).put("source", "vars.missing");
        else document.put("bodyTemplate", "{{ broken");
        Files.writeString(selectedFile, yaml.writeValueAsString(document));
        scenarios.reload();
        var invalid = bundleService().validateExisting("roots");
        assertThat(invalid.ok()).isFalse();
        assertThat(invalid.findings()).extracting(finding -> finding.code())
            .contains(workerRole.equals("request-builder") ? "TEMPLATE_INVALID" : "VARIABLES_MISSING");
    }

    private ObjectNode createScenario(String workerRole, String field, String location) throws Exception {
        Files.writeString(capabilitiesDir.resolve(workerRole + "-manifest.json"),
            Files.readString(capabilitiesDir.resolve("worker-manifest.json"))
                .replace("worker-image", workerRole).replace("\"worker\"", "\"" + workerRole + "\""));
        capabilities.reload();
        writeBundleScenario("roots");
        ObjectNode scenario = (ObjectNode) yaml.readTree(Files.readString(scenariosDir.resolve("roots/scenario.yaml")));
        var bee = ((ObjectNode) scenario.get("template")).putArray("bees").addObject();
        bee.put("role", "target").put("image", workerRole + ":latest");
        bee.putObject("work");
        var config = bee.putObject("config");
        config.putObject("inputs").put("type", "RABBITMQ");
        config.putObject("outputs").put("type", "NONE");
        config.put("serviceId", "accounts").put(field, location);
        if (workerRole.equals("clearing-export"))
            config.put("mode", "structured").put("schemaId", "pcs-clearing").put("schemaVersion", "1.0.0");
        writeScenario(scenario);
        return scenario;
    }

    private void writeScenario(ObjectNode scenario) throws Exception {
        Files.writeString(scenariosDir.resolve("roots/scenario.yaml"), yaml.writeValueAsString(scenario));
    }

    private void writeSelectedDocument(String workerRole, Path bundle) throws Exception {
        writeSelectedDocument(workerRole, bundle, true);
    }

    private void writeSelectedDocument(String workerRole, Path bundle, boolean invalid) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        if (workerRole.equals("clearing-export")) {
            Path original = repo.resolve("scenarios/bundles/clearing-export-structured-demo/clearing-schemas/pcs-clearing/1.0.0/schema.yaml");
            ObjectNode schema = (ObjectNode) yaml.readTree(Files.readString(original));
            if (invalid) ((ObjectNode) schema.at("/recordMapping/payload")).put("expression", "{{ eval('vars.missing') }}");
            Files.writeString(Files.createDirectories(bundle.resolve("clearing-schemas/pcs-clearing/1.0.0")).resolve("schema.yaml"),
                yaml.writeValueAsString(schema));
        } else if (workerRole.equals("db-query")) {
            ObjectNode query = (ObjectNode) yaml.readTree(Files.readString(repo.resolve(
                "scenarios/bundles/db-query-postgres-smoke/templates/db/db/smoke.yaml")));
            if (invalid) ((ObjectNode) query.at("/params/0")).put("source", "vars.missing");
            Files.writeString(Files.createDirectories(bundle.resolve("templates")).resolve("query.yaml"), yaml.writeValueAsString(query));
        } else {
            Files.writeString(Files.createDirectories(bundle.resolve("templates")).resolve("call.yaml"), """
                protocol: HTTP
                serviceId: accounts
                callId: create
                method: POST
                pathTemplate: /test
                bodyTemplate: '%s'
                """.formatted(invalid ? "{{ broken" : "literal"));
        }
    }
}
