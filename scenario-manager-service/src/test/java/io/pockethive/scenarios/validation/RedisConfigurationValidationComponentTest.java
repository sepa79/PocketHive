package io.pockethive.scenarios.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.capabilities.CapabilityCatalogueService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RedisConfigurationValidationComponentTest {
    @TempDir Path bundle;
    private ScenarioBundleValidator validator;

    @BeforeEach
    void loadCatalogue() throws Exception {
        var catalogue = new CapabilityCatalogueService(Path.of("capabilities"), io.pockethive.scenarios.config.ScenarioWorkConfigurationComposition.createMutationPolicyRegistry());
        catalogue.reload();
        validator = new ScenarioBundleValidator(catalogue, "latest", "test", new io.pockethive.work.config.composition.CurrentWorkConfigurationProviders().workConfigurationParser());
    }

    @ParameterizedTest
    @MethodSource("deferredRoutes")
    void bundleReportsDeferredRoutesWithoutCompetingTypeOrTargetErrors(String routes, String suffix) throws Exception {
        var result = validate(routes);
        assertThat(result.ok()).as("%s", result.findings()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.WARNING);
            assertThat(finding.path()).isEqualTo("scenario.yaml:template.bees[0].config.outputs.redis.routes" + suffix);
        });
    }

    static Stream<Arguments> deferredRoutes() {
        return Stream.of(
            Arguments.of("\"{{ '[]' }}\"", ""),
            Arguments.of("\"{% if true %}[]{% endif %}\"", ""),
            Arguments.of("[{match: '.*', header: \"{{ '' }}\", list: out}]", "[0].header"));
    }

    @ParameterizedTest
    @MethodSource("invalidRoutes")
    void bundleRetainsConcreteTypeAndMissingTargetErrors(String routes, String suffix, String message) throws Exception {
        var result = validate(routes);
        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
            assertThat(finding.path()).isEqualTo("scenario.yaml:template.bees[0].config.outputs.redis" + suffix);
            assertThat(finding.message()).contains(message);
        });
    }

    static Stream<Arguments> invalidRoutes() {
        return Stream.of(
            Arguments.of("7", ".routes", "routes must be a list"),
            Arguments.of("[]", "", "requires at least one target"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{ '[]' }}", "{% if true %}[]{% endif %}"})
    void bundleDefersSymbolicSourcesWithoutCompetingTypeErrors(String sources) throws Exception {
        var result = validateSources("\"" + sources + "\"");
        assertThat(result.ok()).as("%s", result.findings()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
            assertThat(finding.path()).endsWith(".inputs.redis.sources");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{listName: red, weight: bad}]",
        "[{listName: red, weight: 1}, {listName: ' red ', weight: 2}]",
        "[{listName: red, weight: 1, unknown: true}]",
        "[{listName: \"\\0\", weight: 1}]"
    })
    void bundleRejectsInvalidSourceEntries(String sources) throws Exception {
        var result = validateSources(sources);
        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
            assertThat(finding.path()).contains(".inputs.redis.sources[");
        });
    }

    @Test
    void bundleAcceptsConcreteSourcesWithNumericPropertyText() throws Exception {
        var result = validateSources("[{listName: red, weight: '1.5'}, {listName: blue, weight: 2}]");
        assertThat(result.findings()).isEmpty();
        assertThat(result.ok()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{ 'red' }}", "{% if true %}red{% endif %}"})
    void bundleDefersSingleListNameThroughTheSharedSelectionContract(String name) throws Exception {
        var result = validateSources("[]", "\"" + name + "\"");
        assertThat(result.ok()).as("%s", result.findings()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
            assertThat(finding.path()).endsWith(".inputs.redis.listName");
        });
    }

    @Test
    void bundleProjectsPickStrategyThroughTheCompleteDatasetSettingsContract() throws Exception {
        var result = validateSources("[]", "dataset", "RANDOM");

        assertThat(result.ok()).isFalse();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
            assertThat(finding.path()).endsWith(".inputs.redis.pickStrategy");
        });
    }

    private BundleValidationResult validateSources(String sources) throws Exception {
        return validateSources(sources, "\"\"");
    }

    private BundleValidationResult validateSources(String sources, String name) throws Exception {
        return validateSources(sources, name, "ROUND_ROBIN");
    }

    private BundleValidationResult validateSources(String sources, String name, String pickStrategy) throws Exception {
        return validateIo("""
            type: REDIS_DATASET
            redis:
              host: redis
              port: 6379
              ssl: false
              pickStrategy: PICK_STRATEGY
              ratePerSec: 1
              listName: LIST_NAME
              sources: SOURCE_VALUE
            """.replace("SOURCE_VALUE", sources).replace("LIST_NAME", name)
            .replace("PICK_STRATEGY", pickStrategy), "type: NONE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"defaultList", "targetListTemplate"})
    void bundleRejectsNontextualTargetsEvenWithAValidRoute(String field) throws Exception {
        var result = validateOutput("[{match: '.*', list: out}]", field.equals("defaultList") ? "7" : "\"\"",
            field.equals("targetListTemplate") ? "{nested: out}" : "\"\"");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
            assertThat(finding.path()).endsWith(".outputs.redis." + field);
            assertThat(finding.message()).contains("target must be text");
        });
    }

    @Test
    void bundleDistinguishesBootstrapDefaultsFromMessageTemplates() throws Exception {
        var deferred = validateOutput("[]", "\"{{ 'out' }}\"", "\"\"");
        assertThat(deferred.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
            assertThat(finding.path()).endsWith(".outputs.redis.defaultList");
        });
        var messageTemplate = validateOutput("[]", "\"\"", "\"{{ headers.target }}\"");
        assertThat(messageTemplate.findings()).isEmpty();
    }

    private BundleValidationResult validate(String routes) throws Exception {
        return validateOutput(routes, "\"\"", "\"\"");
    }

    private BundleValidationResult validateOutput(String routes, String defaultList, String template) throws Exception {
        return validateOutput(routes, defaultList, template, "LAST", "RPUSH", "-1");
    }

    @Test
    void bundleDefersWriteExpressionsWithoutCompetingOptionOrTypeErrors() throws Exception {
        var result = validateOutput("[]", "out", "\"\"", "\"{{ 'FIRST' }}\"", "\"{{ 'LPUSH' }}\"", "\"{{ 5 }}\"");
        assertThat(result.ok()).isTrue();
        assertThat(result.findings()).extracting(ValidationFinding::code)
            .containsExactly(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code(), ValidationIssue.WORK_CONFIGURATION_DEFERRED.code(),
                ValidationIssue.WORK_CONFIGURATION_DEFERRED.code());
    }

    @Test
    void bundleUsesCanonicalWriteConstraintsForConcreteAndMissingValues() throws Exception {
        var invalid = validateOutput("[]", "out", "\"\"", "null", "WRONG", "2147483648");
        assertThat(invalid.findings()).hasSize(3).allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
            assertThat(finding.path()).contains(".outputs.redis.");
        });
        var valid = validateOutput("[]", "out", "\"\"", "' first '", "lpush", "'0'");
        assertThat(valid.findings()).isEmpty();
    }

    private BundleValidationResult validateOutput(String routes, String defaultList, String template,
                                                   String source, String direction, String maxLen) throws Exception {
        return validateIo("type: RABBITMQ", """
            type: REDIS
            redis:
              host: redis
              port: 6379
              ssl: false
              sourceStep: SOURCE_VALUE
              pushDirection: DIRECTION_VALUE
              routes: ROUTE_VALUE
              defaultList: DEFAULT_VALUE
              targetListTemplate: TEMPLATE_VALUE
              maxLen: LENGTH_VALUE
            """.replace("ROUTE_VALUE", routes).replace("DEFAULT_VALUE", defaultList).replace("TEMPLATE_VALUE", template)
            .replace("SOURCE_VALUE", source).replace("DIRECTION_VALUE", direction).replace("LENGTH_VALUE", maxLen));
    }

    @Test
    void projectsConnectionErrorsAndDeferredFieldsWithoutCatalogueDuplicates() throws Exception {
        var output = """
            type: REDIS
            redis:
              host: HOST
              port: PORT
              ssl: SSL
              sourceStep: LAST
              pushDirection: RPUSH
              maxLen: -1
              routes: []
              defaultList: out
              targetListTemplate: ""
            """;
        var invalid = validateIo("type: RABBITMQ", output.replace("HOST", "7").replace("PORT", "0").replace("SSL", "'yes'"));
        assertThat(invalid.findings()).extracting(ValidationFinding::path).containsExactly(
            "scenario.yaml:template.bees[0].config.outputs.redis.host",
            "scenario.yaml:template.bees[0].config.outputs.redis.port",
            "scenario.yaml:template.bees[0].config.outputs.redis.ssl");
        var deferred = validateIo("type: RABBITMQ", output.replace("HOST", "\"{{ 'redis' }}\"")
            .replace("PORT", "\"{{ 6379 }}\"").replace("SSL", "\"{{ false }}\""));
        assertThat(deferred.ok()).isTrue();
        assertThat(deferred.findings()).hasSize(3).allSatisfy(finding ->
            assertThat(finding.code()).isEqualTo(ValidationIssue.WORK_CONFIGURATION_DEFERRED.code()));
    }

    private BundleValidationResult validateIo(String input, String output) throws Exception {
        Files.writeString(bundle.resolve("scenario.yaml"), """
            protocolVersion: "2.0.0"
            id: redis-configuration-validation
            name: Redis configuration validation
            template:
              image: swarm-controller:latest
              bees:
                - role: processor
                  image: processor:latest
                  work: {}
                  config:
                    baseUrl: "http://example.invalid"
                    mode: THREAD_COUNT
                    threadCount: 1
                    inputs:
            INPUT_SETTINGS
                    outputs:
            OUTPUT_SETTINGS
            """.replace("INPUT_SETTINGS\n", input.indent(10)).replace("OUTPUT_SETTINGS\n", output.indent(10)));
        return validator.validate(new BundleValidationInput(BundleValidationSource.SCENARIO_MANAGER,
            bundle, "redis", "redis", null, List.of(), null));
    }
}
