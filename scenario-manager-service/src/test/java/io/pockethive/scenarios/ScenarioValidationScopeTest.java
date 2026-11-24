package io.pockethive.scenarios;

import io.pockethive.swarm.model.ScenarioConfigurationPreparation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
class ScenarioValidationScopeTest extends ScenarioComponentTestFixture {
    @ParameterizedTest
    @ValueSource(strings = {"{{ pickWeightedSeeded('selection-label', 'seed-value', 'create', 1) }}",
        "{{ eval('1 + 1') }}"})
    void dynamicSelectorsDoNotInventReferencesFromFunctionArguments(String selector) throws Exception {
        createBundle(selector, "SIMPLE", "", "");
        assertAvailable();
    }

    @Test
    void configurationVariablesAreOpaqueForSyntaxAndSelectorDiscovery() throws Exception {
        createBundle("create", "SIMPLE", "vars: {business: {callId: invented, x-ph-call-id: invented, text: 'vars.customerId', literal: '{{ broken'}}", "");
        assertAvailable();
    }

    @Test
    void businessCallIdsInConsumerDataDoNotSelectTemplates() throws Exception {
        createBundle("create", "SIMPLE", "", "business: {callId: invented, serviceId: invented, x-ph-call-id: invented}");
        assertAvailable();
    }

    @Test
    void metadataCommentsAndUnrelatedDocumentsAreNotVariableDependencies() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Path scenario = scenariosDir.resolve("scope/scenario.yaml");
        Files.writeString(scenario, Files.readString(scenario).replace("name: Scope", "name: vars.customerId\n# vars.comment"));
        Files.writeString(scenario.getParent().resolve("external.yaml"), "text: vars.external\n");
        assertAvailable();
    }

    @Test
    void httpEnvelopeHeadersAreNotWorkSelectors() throws Exception {
        createBundle("invented", "HTTP", "", "");
        assertAvailable();
    }

    @Test
    void realLiteralMissingSelectorsStillFail() throws Exception {
        createBundle("missing", "SIMPLE", "", "");
        scenarios.reload();
        var result = bundleService().validateExisting("scope");
        assertThat(result.findings()).anySatisfy(finding ->
            assertThat(finding.code()).isEqualTo("TEMPLATE_CALL_ID_MISSING"));
        assertThat(scenarios.listAvailableSummaries()).isEmpty();
    }

    @Test
    void literalVariableTextInARequestTemplateIsData() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template("literal vars.customerId"));
        assertAvailable();
    }

    @Test
    void actualUnknownVariablesStillFailWithoutDefinitions() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template("{{ vars.customerId }}"));
        scenarios.reload();
        var result = bundleService().validateExisting("scope");
        assertThat(result.findings()).anySatisfy(finding ->
            assertThat(finding.code()).isEqualTo("VARIABLES_MISSING"));
        assertThat(scenarios.listAvailableSummaries()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{ eval('') }}", "{{ eval('   ') }}",
        "{% if false %}{{ eval('') }}{% endif %}"})
    void blankEvalRemainsAnAvailableNativeTemplate(String source) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template(source));
        assertAvailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#root.vars.optional", "#this.vars.optional"})
    void explicitSpelRootReadsRetainMissingUnknownAndOptionalVariableChecks(String expression) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template("{{ eval('" + expression + "') }}"));
        assertFinding("VARIABLES_MISSING");
        Path variables = scenariosDir.resolve("scope/variables.yaml");
        Files.writeString(variables, """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: other, type: STRING, scope: GLOBAL, required: false}]
            """);
        assertFinding("VARIABLE_REFERENCE_UNKNOWN");
        Files.writeString(variables, Files.readString(variables).replace("name: other", "name: optional"));
        assertAvailable();
        assertThat(new ScenarioVariablesService(scenarios, validator).resolve("scope", "default", null).vars()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"generator", "clearing-export", "http-sequence"})
    void customImageNamesKeepTheSelectedWorkersTemplateChecks(String workerRole) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        String image = "custom-" + workerRole;
        installImage(image, workerRole);
        int index = workerRole.equals("http-sequence") ? 1 : 0;
        mutateImage(index, image);
        for (String source : new String[] {"{{ broken", "{{ eval('vars.missing') }}", "literal"}) {
            mutateConfig(index, config -> {
                switch (workerRole) {
                    case "generator" -> putPath(config, "message.body", source);
                    case "clearing-export" -> config.put("mode", "TEMPLATE").put("recordTemplate", source);
                    case "http-sequence" -> config.putArray("steps").addObject().put("callId", "create")
                        .putArray("set").addObject().put("to", "answer").put("template", source);
                    default -> throw new IllegalArgumentException(workerRole);
                }
            });
            if (source.equals("{{ broken")) assertFinding("TEMPLATE_INVALID");
            else if (source.contains("vars.missing")) assertFinding("VARIABLES_MISSING");
            else assertAvailable();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{{'vars': {'business': 'value'}}}.![#this.vars['business']][0]",
        "{{'vars': {'business': 'value'}}}.?[#this.vars['business'] == 'value'][0].vars['business']"
    })
    void localSpelElementsRemainAvailableWithoutScenarioVariables(String expression) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template("{{ eval(\"" + expression + "\") }}"));
        assertAvailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{{'x': 'value'}}.![#this[vars.foo]][0]",
        "{{'x': 'value'}}.?[#this[vars.foo] == 'value'][0]['x']"
    })
    void spelIndexesRetainScenarioVariableChecksInsideLocalElements(String expression) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template("{{ eval(\"" + expression + "\") }}"));
        assertFinding("VARIABLES_MISSING");
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: foo, type: STRING, scope: GLOBAL, required: false}]
            """);
        assertAvailable();
    }

    @Test
    void optionalDeclaredVariablesRemainOptionalAndValuesStayOpaque() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template("{{ vars.optional | default('fallback') }}"));
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions:
              - {name: optional, type: STRING, scope: GLOBAL, required: false}
              - {name: business, type: OBJECT, scope: GLOBAL, required: true}
            values:
              global:
                default:
                  business: {text: 'vars.literal', callId: invented, body: '{{ broken'}
            """);
        assertAvailable();
        var variables = new ScenarioVariablesService(scenarios, validator);
        assertThat(variables.resolve("scope", "default", null).vars()).doesNotContainKey("optional");
    }

    @Test
    void requestFieldsOutsideTheDeclaredRootRemainOrdinaryData() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Path bundle = scenariosDir.resolve("scope");
        Files.createDirectories(bundle.resolve("templates/requests"));
        Files.move(bundle.resolve("templates/call.yaml"), bundle.resolve("templates/requests/call.yaml"));
        Path scenario = bundle.resolve("scenario.yaml");
        Files.writeString(scenario, Files.readString(scenario).replace("/templates", "/templates/requests"));
        Files.writeString(bundle.resolve("templates/business.yaml"), "protocol: BUSINESS\nprofile: customer\ntext: vars.opaque\n");
        assertAvailable();
    }

    @Test
    void blankServiceHeaderKeepsTheExistingRequestBuilderDefault() throws Exception {
        createBundle("missing", "SIMPLE", "", "");
        Path scenario = scenariosDir.resolve("scope/scenario.yaml");
        var yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(Files.readString(scenario));
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.at("/template/bees/0/config/message/headers"))
            .put("x-ph-service-id", "  ");
        Files.writeString(scenario, yaml.writeValueAsString(document));
        scenarios.reload();
        assertThat(bundleService().validateExisting("scope").findings()).anySatisfy(finding ->
            assertThat(finding.code()).isEqualTo("TEMPLATE_CALL_ID_MISSING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"message.body", "message.path", "message.method", "message.headers.X-Test",
        "interceptors.templating.template", "outputs.redis.targetListTemplate", "interceptors.redisUploader.targetListTemplate"})
    void actualOwnerSourcesKeepVariableChecksEvenWithOtherDeferredSettings(String field) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        mutateConfig(0, config -> {
            if (field.startsWith("outputs.redis")) {
                config.putObject("outputs").put("type", "REDIS").putObject("redis")
                    .put("host", "redis").put("port", "{{ eval('1 + 1') }}").put("ssl", false)
                    .put("sourceStep", "LAST").put("pushDirection", "RPUSH").put("maxLen", -1);
            }
            if (field.startsWith("interceptors.redisUploader")) {
                config.putObject("interceptors").putObject("redisUploader").put("enabled", true);
            }
            putPath(config, field, "{{ eval('vars.missing') }}");
        });
        assertFinding("VARIABLES_MISSING");
    }

    @Test
    void arbitraryBusinessExpressionsAreNotCompiledAsRuntimeTemplates() throws Exception {
        createBundle("create", "SIMPLE", "", "business: {body: '{{ broken', sqlTemplate: 'vars.literal'}");
        assertAvailable();
    }

    @Test
    void disabledUploaderDoesNotCompileItsDormantTemplate() throws Exception {
        createBundle("create", "SIMPLE", "", "interceptors: {redisUploader: {enabled: false, targetListTemplate: '{{ broken'}}");
        assertAvailable();
    }

    @Test
    void authSourcesKeepMissingAndUnknownVariableChecksWithoutReadingSecrets() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/authProfiles.yaml"), """
            profiles:
              api:
                type: STATIC_TOKEN
                storage: {mode: NONE}
                token: "{{ eval('vars.missing') }}"
            """);
        assertFinding("VARIABLES_MISSING");
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: optional, type: STRING, scope: GLOBAL, required: false}]
            """);
        assertFinding("VARIABLE_REFERENCE_UNKNOWN");
        Path auth = scenariosDir.resolve("scope/authProfiles.yaml");
        Files.writeString(auth, Files.readString(auth).replace("vars.missing", "vars.optional"));
        assertAvailable();
        assertThat(new ScenarioVariablesService(scenarios, validator).resolve("scope", "default", null).vars()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "file"})
    void authSecretReferencesAreOpaqueAndNeverReadByValidation(String kind) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/authProfiles.yaml"), """
            profiles:
              api:
                type: STATIC_TOKEN
                storage: {mode: NONE}
                token: {%s: "{{ broken vars.notARead"}
            """.formatted(kind));
        assertAvailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fileNameTemplate", "headerTemplate", "recordTemplate", "footerTemplate"})
    void clearingTemplateModeSourcesAreOwnedAndCompiled(String field) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage("clearing-export");
        mutateImage(0, "clearing-export");
        mutateConfig(0, config -> { config.put("mode", " template "); config.put(field, "{{ broken"); });
        assertFinding("TEMPLATE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"template", "structured"})
    void symbolicClearingModeDefersOnlyItsConditionalSources(String selectedMode) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage("clearing-export");
        mutateImage(0, "clearing-export");
        mutateConfig(0, config -> config.put("mode", "{{ vars.exportMode }}")
            .put("recordTemplate", "{{ broken")
            .put("schemaRegistryRoot", ScenarioBundleLayout.CONTAINER_ROOT + "/missing-clearing-schemas")
            .put("schemaId", "missing").put("schemaVersion", "1"));
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: exportMode, type: STRING, scope: GLOBAL, required: true}]
            values:
              global:
                default: {exportMode: %s}
            """.formatted(selectedMode));

        scenarios.reload();
        var result = bundleService().validateExisting("scope");
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("WORK_CONFIGURATION_DEFERRED");
            assertThat(finding.severity().name()).isEqualTo("WARNING");
            assertThat(finding.path()).isEqualTo("scenario.yaml:template.bees[0].config.mode");
        });
        assertThat(new ScenarioVariablesService(scenarios, validator).resolve("scope", "default", null).vars())
            .containsEntry("exportMode", selectedMode);

        mutateConfig(0, config -> config.putObject("interceptors").putObject("templating")
            .put("template", "{{ eval('vars.missing') }}"));
        assertFinding("VARIABLE_REFERENCE_UNKNOWN");
        assertThat(bundleService().validateExisting("scope").findings()).filteredOn(finding ->
            finding.code().equals("WORK_CONFIGURATION_DEFERRED")).hasSize(1);

        mutateConfig(0, config -> { config.remove("interceptors"); config.put("mode", selectedMode); });
        assertFinding(selectedMode.equals("template") ? "TEMPLATE_INVALID" : "TEMPLATE_PARSE_ERROR");
        assertThat(bundleService().validateExisting("scope").findings()).noneSatisfy(finding ->
            assertThat(finding.code()).isEqualTo("WORK_CONFIGURATION_DEFERRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"clearing-export", "custom-clearing-export"})
    void structuredClearingUsesTheSelectedSchemaAndKeepsXmlMetadataOpaque(String image) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage(image, "clearing-export");
        mutateImage(0, image);
        mutateConfig(0, config -> config.put("mode", " structured ")
            .put("schemaRegistryRoot", ScenarioBundleLayout.CONTAINER_ROOT + "/clearing-schemas")
            .put("schemaId", "demo").put("schemaVersion", "1").put("recordTemplate", "{{ broken"));
        Path schema = Files.createDirectories(scenariosDir.resolve("scope/clearing-schemas/demo/1")).resolve("schema.yaml");
        Files.writeString(schema, """
            schemaId: demo
            schemaVersion: '1'
            fileNameTemplate: clearing.xml
            headerMapping: {literal: 'vars.opaque'}
            footerMapping: {}
            recordMapping: {id: {expression: '{{ broken'}}
            xml: {business: '{{ broken'}
            """);
        assertFinding("TEMPLATE_INVALID");
        Files.writeString(schema, Files.readString(schema).replace("expression: '{{ broken'", "expression: 'vars.literal'"));
        assertAvailable();
    }

    @Test
    void httpSequenceSetTemplatesUseTheSharedStepContract() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage("http-sequence");
        mutateImage(1, "http-sequence");
        mutateConfig(1, config -> config.putArray("steps").addObject().put("callId", "create")
            .putArray("set").addObject().put("to", "answer").put("template", "{{ eval('vars.missing') }}"));
        assertFinding("VARIABLES_MISSING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"db-query", "custom-db-query"})
    void dbParameterSourcesKeepVariableChecksWhileSqlAndCommentsStayData(String image) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage(image, "db-query");
        mutateImage(1, image);
        Path template = scenariosDir.resolve("scope/templates/call.yaml");
        Files.writeString(template, """
            serviceId: accounts
            queryId: lookup
            adapter: POSTGRES
            statementType: SELECT
            sqlTemplate: "select :id -- vars.comment {{ broken"
            result: {mode: FIRST_ROW}
            params: [{name: id, source: vars.customer.id, type: STRING}]
            """);
        assertFinding("VARIABLES_MISSING");
        Files.writeString(template, Files.readString(template).replace("source: vars.customer.id", "source: payload.customer.id"));
        assertAvailable();
    }

    @Test
    void symbolicUploaderEnabledIsDeferredWithoutBreakingValidation() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        mutateConfig(0, config -> config.putObject("interceptors").putObject("redisUploader")
            .put("enabled", "{{ eval('true') }}").put("targetListTemplate", "{{ eval('vars.missing') }}"));
        assertFinding("VARIABLES_MISSING");
    }

    @Test
    void invalidConcreteUploaderEnabledProducesAFinding() throws Exception {
        createBundle("create", "SIMPLE", "", "interceptors: {redisUploader: {enabled: nonsense}}");
        assertFinding("SCENARIO_DESCRIPTOR_INVALID");
    }

    @Test
    void wholeValueHeaderMapStillResolvesThroughExistingBootstrap() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        mutateConfig(0, config -> ((com.fasterxml.jackson.databind.node.ObjectNode) config.get("message"))
            .put("headers", "{{ vars.headerMap }}"));
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: headerMap, type: OBJECT, scope: GLOBAL, required: true}]
            values:
              global:
                default:
                  headerMap: {x-ph-call-id: create}
            """);
        assertAvailable();
        var vars = new ScenarioVariablesService(scenarios, validator).resolve("scope", "default", null).vars();
        assertThat(ScenarioConfigurationPreparation.applyVariables(
            java.util.Map.of("message", java.util.Map.of("headers", "{{ vars.headerMap }}")), vars))
            .isEqualTo(java.util.Map.of("vars", vars, "message", java.util.Map.of("headers", java.util.Map.of("x-ph-call-id", "create"))));
    }

    @Test
    void wholeValueSetObjectsStayDeferredAndConcreteNeighborsStayChecked() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage("http-sequence");
        mutateImage(1, "http-sequence");
        mutateConfig(1, config -> {
            var sets = config.putArray("steps").addObject().put("callId", "create").putArray("set");
            sets.add("{{ vars.setter }}");
            sets.addObject().put("to", "answer").put("template", "{{ broken");
        });
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: setter, type: OBJECT, scope: GLOBAL, required: true}]
            values:
              global:
                default:
                  setter: {to: answer, template: 'literal'}
            """);
        assertFinding("TEMPLATE_INVALID");
        mutateConfig(1, config -> ((com.fasterxml.jackson.databind.node.ObjectNode) config.at("/steps/0/set/1"))
            .put("template", "literal"));
        assertAvailable();
    }

    @Test
    void httpSequenceDoesNotRenderPebbleInItsCallId() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage("http-sequence");
        mutateImage(1, "http-sequence");
        mutateConfig(1, config -> config.putArray("steps").addObject().put("callId", "{{ eval('1 + 1') }}"));
        assertFinding("TEMPLATE_CALL_ID_MISSING");
    }

    @Test
    void structuredSchemaSelectionWaitsForBootstrapVariables() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage("clearing-export");
        mutateImage(0, "clearing-export");
        mutateConfig(0, config -> config.put("mode", "structured")
            .put("schemaRegistryRoot", ScenarioBundleLayout.CONTAINER_ROOT + "/clearing-schemas")
            .put("schemaId", "{{ vars.schemaId }}").put("schemaVersion", "1"));
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: schemaId, type: STRING, scope: GLOBAL, required: true}]
            values: {global: {default: {schemaId: demo}}}
            """);
        assertAvailable();
        assertThat(bundleService().validateExisting("scope").findings()).anySatisfy(finding ->
            assertThat(finding.code()).isEqualTo("WORK_CONFIGURATION_DEFERRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"authRef: {profileId: business, applyAs: HTTP_AUTHORIZATION_BEARER}",
        "auth: business-data", "- scalar-business-data"})
    void documentsOutsideSelectedRequestRootsDoNotBecomeAuthContracts(String document) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Path bundle = scenariosDir.resolve("scope");
        Files.createDirectories(bundle.resolve("templates/requests"));
        Files.move(bundle.resolve("templates/call.yaml"), bundle.resolve("templates/requests/call.yaml"));
        mutateConfig(1, config -> config.put("templateRoot", ScenarioBundleLayout.CONTAINER_ROOT + "/templates/requests"));
        Files.writeString(bundle.resolve("templates/business.yaml"), document + "\n");
        assertAvailable();
    }

    @Test
    void deferredSetFlagDoesNotMaskItsConcreteTemplate() throws Exception {
        createBundle("create", "SIMPLE", "", "");
        installImage("http-sequence");
        mutateImage(1, "http-sequence");
        mutateConfig(1, config -> config.putArray("steps").addObject().put("callId", "create").putArray("set")
            .addObject().put("to", "answer").put("parseJson", "{{ eval('true') }}").put("template", "{{ broken"));
        assertFinding("TEMPLATE_INVALID");
        mutateConfig(1, config -> ((com.fasterxml.jackson.databind.node.ObjectNode) config.at("/steps/0/set/0"))
            .put("template", "literal"));
        assertAvailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{% filter upper %}{{ vars.foo }}{% endfilter %}",
        "{% block x %}A{% endblock %}{{ block(vars.foo) }}"})
    void runtimeBlocksRemainValidNativeTemplatesAndKeepActualDependencies(String source) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template(source));
        assertFinding("VARIABLES_MISSING");
        Files.writeString(scenariosDir.resolve("scope/variables.yaml"), """
            version: 1
            profiles: [{id: default, name: Default}]
            definitions: [{name: foo, type: STRING, scope: GLOBAL, required: false}]
            """);
        assertAvailable();
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "{% set vars = {'customerId': 'local'} %}{{ vars.customerId }}",
        "{% for vars in [{'customerId': 'local'}] %}{{ vars.customerId }}{% endfor %}",
        "{% import 'macros' as vars %}"
    })
    void selectedRequestTemplatesRejectReservedVarsBindings(String source) throws Exception {
        createBundle("create", "SIMPLE", "", "");
        Files.writeString(scenariosDir.resolve("scope/templates/call.yaml"), template(source));
        scenarios.reload();
        var result = bundleService().validateExisting("scope");
        assertThat(result.ok()).as("findings: %s", result.findings()).isFalse();
        assertThat(result.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TEMPLATE_INVALID");
            assertThat(finding.path()).startsWith("templates/call.yaml");
            assertThat(finding.message()).contains("vars", "reserved");
        });
        assertThat(result.findings()).noneSatisfy(finding ->
            assertThat(finding.code()).isEqualTo("VARIABLES_MISSING"));
        assertThat(scenarios.listAvailableSummaries()).isEmpty();
    }

    private void installImage(String image) throws Exception {
        installImage(image, image);
    }

    private void installImage(String image, String workerRole) throws Exception {
        Files.writeString(capabilitiesDir.resolve(image + "-manifest.json"),
            Files.readString(capabilitiesDir.resolve("worker-manifest.json"))
                .replace("worker-image", image).replace("\"worker\"", "\"" + workerRole + "\""));
        capabilities.reload();
    }

    private final com.fasterxml.jackson.databind.ObjectMapper yaml = new com.fasterxml.jackson.databind.ObjectMapper(
        new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

    private void mutateImage(int index, String image) throws Exception {
        Path scenario = scenariosDir.resolve("scope/scenario.yaml");
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(Files.readString(scenario));
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.at("/template/bees/" + index)).put("image", image + ":latest");
        Files.writeString(scenario, yaml.writeValueAsString(document));
    }

    private void mutateConfig(int index, java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> edit) throws Exception {
        Path scenario = scenariosDir.resolve("scope/scenario.yaml");
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(Files.readString(scenario));
        edit.accept((com.fasterxml.jackson.databind.node.ObjectNode) document.at("/template/bees/" + index + "/config"));
        Files.writeString(scenario, yaml.writeValueAsString(document));
    }

    private void putPath(com.fasterxml.jackson.databind.node.ObjectNode node, String path, String value) {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) node = node.withObject("/" + parts[i]);
        node.put(parts[parts.length - 1], value);
    }

    private void assertFinding(String code) throws Exception {
        scenarios.reload();
        assertThat(bundleService().validateExisting("scope").findings()).extracting(finding -> finding.code()).contains(code);
        assertThat(scenarios.listAvailableSummaries()).isEmpty();
    }

    private void createBundle(String selector, String bodyType, String producerExtra, String consumerExtra) throws Exception {
        for (String image : new String[] {"request-builder", "generator"}) {
            Files.writeString(capabilitiesDir.resolve(image + "-manifest.json"),
                Files.readString(capabilitiesDir.resolve("worker-manifest.json"))
                    .replace("worker-image", image).replace("\"worker\"", "\"" + image + "\""));
        }
        capabilities.reload();
        Path bundle = Files.createDirectories(scenariosDir.resolve("scope"));
        Files.writeString(bundle.resolve("scenario.yaml"), """
            protocolVersion: "2.0.0"
            id: scope
            name: Scope
            template:
              image: ctrl-image:latest
              bees:
                - role: source
                  image: generator:latest
                  work: {out: {out: build}}
                  config:
                    inputs: {type: SCHEDULER, scheduler: {ratePerSec: 1, maxMessages: 0}}
                    outputs: {type: RABBITMQ}
                    %s
                    message:
                      bodyType: %s
                      path: /test
                      method: POST
                      body: '{}'
                      headers:
                        x-ph-call-id: "%s"
                - role: builder
                  image: request-builder:latest
                  work: {in: {in: build}}
                  config:
                    inputs: {type: RABBITMQ}
                    outputs: {type: NONE}
                    templateRoot: %s/templates
                    serviceId: accounts
                    %s
            """.formatted(producerExtra, bodyType, selector, ScenarioBundleLayout.CONTAINER_ROOT, consumerExtra));
        Files.writeString(Files.createDirectories(bundle.resolve("templates")).resolve("call.yaml"), template("{}"));
    }

    private String template(String body) throws com.fasterxml.jackson.core.JsonProcessingException {
        return """
            protocol: HTTP
            serviceId: accounts
            callId: create
            method: POST
            pathTemplate: /test
            bodyTemplate: %s
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
    }

    private void assertAvailable() throws Exception {
        scenarios.reload();
        var result = bundleService().validateExisting("scope");
        assertThat(result.ok()).as("findings: %s", result.findings()).isTrue();
        assertThat(scenarios.listAvailableSummaries()).extracting(ScenarioSummary::id).containsExactly("scope");
    }
}
