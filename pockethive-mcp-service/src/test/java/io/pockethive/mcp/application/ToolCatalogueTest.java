package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.mcp.domain.QaRequirementTopic;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

class ToolCatalogueTest {
    @Test
    void typedToolIdentifiersSerializeAsTheirStableExternalNames() throws Exception {
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(McpToolId.SWARM_CREATE))
            .isEqualTo("\"swarm_create\"");
    }

    @Test
    void everyPublishedToolHasOneCanonicalIdContractAndConnectedSkill() {
        ToolCatalogue catalogue = ToolCatalogue.canonical();

        assertThat(catalogue.tools()).hasSize(59);
        assertThat(catalogue.tools()).allSatisfy(tool -> {
            assertThat(tool.id()).matches("[a-z][a-z0-9_]*");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).containsEntry("type", "object");
            assertThat(tool.outputSchema()).matches(
                schema -> schema.containsKey("type") || schema.containsKey("oneOf"),
                "declare a root type or an explicit root union");
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
            assertThat(tool.inputSchema().get("required")).isInstanceOf(List.class);
            Map<?, ?> properties = (Map<?, ?>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull();
            assertThat(properties.values()).allSatisfy(property ->
                assertThat(((Map<?, ?>) property).containsKey("type")).isTrue());
            assertThat((List<?>) tool.inputSchema().get("required"))
                .allMatch(required -> properties.containsKey(String.valueOf(required)));
            assertThat(tool.owner()).isNotNull();
            assertThat(PocketHiveMcpScopes.ALL).contains(tool.requiredScope());
            assertThat(tool.skillIds()).isNotEmpty()
                .allMatch(catalogue.skills()::containsKey);
        });
        assertThat(new HashSet<>(catalogue.tools().stream().map(ToolDescriptor::id).toList()))
            .hasSize(catalogue.tools().size());
        assertThat(catalogue.tools().stream().map(ToolDescriptor::id))
            .contains("scenario_bundle_tree_read", "scenario_bundle_file_read",
                "scenario_suts_list", "scenario_sut_get", "scenario_workflow_question",
                "scenario_workflow_answer_submit", "scenario_workflow_review_prepare",
                "scenario_workflow_review_submit", "runtime_assess_swarm");
        assertThat(catalogue.requireTool("scenario_list").outputSchema()).containsEntry("type", "array");
        assertThat(catalogue.requireTool("scenario_raw_read").outputSchema()).containsEntry("type", "string");
        assertThat(catalogue.requireTool("swarm_get").outputSchema()).containsEntry("type", "object");
        assertThat(catalogue.skills().values()).allSatisfy(skill -> {
            assertThat(skill.resourceUri()).isEqualTo(
                "pockethive://skills/%s/%s/SKILL.md".formatted(skill.id(), skill.version()));
            assertThat(skill.contentDigest()).startsWith("sha256:").hasSize(71);
            assertThat(skill.markdown().getBytes(StandardCharsets.UTF_8)).isNotEmpty();
            assertThat(catalogue.tools()).anyMatch(tool -> tool.skillIds().contains(skill.id()));
            assertThat(skill.markdown())
                .doesNotContain("pockethive://environment", "pockethive://knowledge/capabilities");
        });
        assertThat(catalogue.skills().get("pockethive-orientation").markdown())
            .contains("pockethive://knowledge/overview", "pockethive://capabilities/current",
                "pockethive://tools/catalogue", "pockethive://skills/catalogue");
        assertThat(catalogue.skills().get("qa-no-inference").version()).isEqualTo("1.2.0");
        assertThat(catalogue.skills().values().stream()
            .filter(skill -> !Set.of("qa-no-inference", "runtime-diagnostics", "live-configuration",
                "governed-cleanup").contains(skill.id())))
            .allSatisfy(skill -> assertThat(skill.version()).isEqualTo("1.0.0"));
        assertThat(catalogue.skills().values().stream()
            .filter(skill -> Set.of("runtime-diagnostics", "live-configuration", "governed-cleanup")
                .contains(skill.id())))
            .allSatisfy(skill -> assertThat(skill.version()).isEqualTo("1.1.0"));
        assertThat(catalogue.skills().get("qa-no-inference").markdown())
            .contains("scenario_workflow_question", "scenario_workflow_answer_submit")
            .contains("scenario_workflow_review_prepare", "scenario_workflow_review_submit")
            .contains("present the returned question unchanged", "wait for the user's explicit response")
            .contains("must not switch modes automatically")
            .contains("goal, SUT, and journey", "material unknowns, conflicts, or unsupported intent");
    }

    @Test
    void highRiskAndBoundedInputsHaveExactMachineReadableConstraints() {
        ToolCatalogue catalogue = ToolCatalogue.canonical();

        Map<String, Object> create = catalogue.requireTool("swarm_create").inputSchema();
        assertThat(create.get("required")).isEqualTo(List.of(
            "swarmId", "templateId", "autoPullImages", "sutId", "variablesProfileId",
            "networkMode", "networkProfileId", "idempotencyKey"));
        assertThat(property(create, "autoPullImages")).containsEntry("type", "boolean");
        assertThat(property(create, "networkMode").get("enum")).isEqualTo(List.of("DIRECT", "PROXIED"));
        assertThat(property(create, "sutId").get("type")).isEqualTo(List.of("string", "null"));
        assertThat(property(create, "variablesProfileId").get("type")).isEqualTo(List.of("string", "null"));
        assertThat(property(create, "networkProfileId").get("type")).isEqualTo(List.of("string", "null"));

        Map<String, Object> readiness = catalogue.requireTool("swarm_wait_ready").inputSchema();
        assertThat(readiness.get("required")).isEqualTo(List.of("swarmId"));
        Map<String, Object> readinessProperties = properties(readiness);
        assertThat(readinessProperties).doesNotContainKey("timeoutSec");

        Map<String, Object> journal = catalogue.requireTool("debug_journal").inputSchema();
        assertThat(journal.get("required")).isEqualTo(List.of("swarmId"));
        assertThat(properties(journal)).containsKeys("swarmId", "runId", "limit", "severity");
        assertThat(property(journal, "limit")).containsEntry("default", 50L);

        Map<String, Object> hiveJournal = catalogue.requireTool("debug_hive_journal").inputSchema();
        assertThat(property(hiveJournal, "limit")).containsEntry("default", 50L);

        Map<String, Object> timeline = catalogue.requireTool("runtime_swarm_timeline").inputSchema();
        assertThat(property(timeline, "limit")).containsEntry("default", 100L);

        Map<String, Object> tap = catalogue.requireTool("debug_tap").inputSchema();
        assertThat(tap.get("required"))
            .isEqualTo(List.of("swarmId", "role", "direction", "ioName", "maxItems", "ttlSeconds"));
        assertThat(property(tap, "maxItems"))
            .containsEntry("type", "integer")
            .containsEntry("minimum", 1L);
        assertThat(property(tap, "ttlSeconds"))
            .containsEntry("type", "integer")
            .containsEntry("minimum", 1L);

        Map<String, Object> tapRead = catalogue.requireTool("debug_tap_read").inputSchema();
        assertThat(tapRead.get("required")).isEqualTo(List.of("tapId"));
        assertThat(property(tapRead, "drain"))
            .containsEntry("type", "integer")
            .containsEntry("minimum", 0L);

        ToolDescriptor runs = catalogue.requireTool("debug_journal_runs");
        assertThat(runs.owner()).isEqualTo(ToolOwner.ORCHESTRATOR);
        assertThat(runs.requiredScope()).isEqualTo(PocketHiveMcpScopes.READ);
        assertThat(runs.skillIds()).containsExactly("runtime-diagnostics");
        assertThat(runs.inputSchema().get("required")).isEqualTo(List.of("swarmId"));

        Map<String, Object> contracts = catalogue.requireTool("scenario_contracts_get").inputSchema();
        assertThat(properties(contracts)).isEmpty();
        assertThat(contracts.get("required")).isEqualTo(List.of());

        Map<String, Object> capabilities = catalogue.requireTool("scenario_capabilities_get").inputSchema();
        assertThat(properties(capabilities)).containsOnlyKeys("all", "imageName", "imageDigest");
        assertThat(capabilities.get("required")).isEqualTo(List.of());
        assertThat(catalogue.requireTool("scenario_capabilities_get").outputSchema())
            .containsEntry("oneOf", List.of(
                Map.of("type", "array", "items", Map.of("type", "object")),
                Map.of("type", "object")));

        Map<String, Object> cleanupPlan = catalogue.requireTool("runtime_cleanup_plan").inputSchema();
        assertThat(cleanupPlan.get("required"))
            .isEqualTo(List.of("swarmId", "includeRunning", "includeRabbit"));
        assertThat(property(cleanupPlan, "includeRunning")).containsEntry("type", "boolean");
        assertThat(property(cleanupPlan, "includeRabbit")).containsEntry("type", "boolean");

        Map<String, Object> cleanupExecute = catalogue.requireTool("runtime_cleanup_execute").inputSchema();
        assertThat(cleanupExecute.get("required")).isEqualTo(List.of(
            "swarmId", "includeRunning", "includeRabbit", "candidateSetHash", "candidateIds",
            "idempotencyKey", "reason"));

        Map<String, Object> publication = catalogue.requireTool("scenario_bundle_publication_prepare").inputSchema();
        assertThat(property(publication, "mode").get("enum")).isEqualTo(List.of("CREATE", "REPLACE"));
        assertThat(property(publication, "archiveDigest").get("pattern"))
            .isEqualTo("^sha256:[0-9a-f]{64}$");

        Map<String, Object> validation = catalogue.requireTool("scenario_bundle_validation_prepare").inputSchema();
        assertThat(property(validation, "source")).containsEntry("type", "object");
        assertThat(property(validation, "fileManifest")).containsEntry("type", "array");

        Map<String, Object> generation = catalogue.requireTool("scenario_workflow_generate").inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> generatedFile = (Map<String, Object>) property(generation, "files").get("items");
        assertThat(property(generatedFile, "content")).containsEntry("minLength", 0);

        Map<String, Object> question = catalogue.requireTool("scenario_workflow_question").inputSchema();
        assertThat(question.get("required")).isEqualTo(List.of("workflowId", "topic"));

        Map<String, Object> submitted = catalogue.requireTool("scenario_workflow_answer_submit").inputSchema();
        assertThat(submitted.get("required")).isEqualTo(List.of(
            "workflowId", "expectedRevision", "topic", "questionId", "requestedSchemaDigest",
            "disposition", "answer"));
        assertThat(property(submitted, "disposition").get("enum")).isEqualTo(List.of(
            "USER_PROVIDED", "USER_CONFIRMED_SOURCE", "NOT_APPLICABLE"));
        assertThat(property(submitted, "requestedSchemaDigest").get("pattern"))
            .isEqualTo("^sha256:[0-9a-f]{64}$");
        assertThat(submitted.get("required")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .doesNotContain("sourceName", "sourceDigest");

        Map<String, Object> review = catalogue.requireTool("scenario_workflow_review_prepare").inputSchema();
        assertThat(review.get("required")).isEqualTo(List.of("workflowId", "expectedRevision", "answers"));
        assertThat(property(review, "answers"))
            .containsEntry("type", "array")
            .containsEntry("minItems", QaRequirementTopic.values().length)
            .containsEntry("maxItems", QaRequirementTopic.values().length);

        Map<String, Object> reviewSubmit = catalogue.requireTool("scenario_workflow_review_submit").inputSchema();
        assertThat(reviewSubmit.get("required")).isEqualTo(List.of(
            "workflowId", "expectedRevision", "reviewId", "requestedSchemaDigest", "answerSetDigest",
            "answers"));
        assertThat(property(reviewSubmit, "answerSetDigest").get("pattern"))
            .isEqualTo("^sha256:[0-9a-f]{64}$");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) properties(schema).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @Test
    void blockedAndRemovedNodeToolsAreNeverPublished() {
        Set<String> published = ToolCatalogue.canonical().tools().stream()
            .map(ToolDescriptor::id)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(published).doesNotContain(
            "debug_queues", "metrics_query", "evidence_summary",
            "mock_wiremock_list", "mock_tcp_list", "bundle_list", "bundle_read",
            "scenario_raw_write", "workflow_hivemind_enrich", "env_switch");
    }

    @Test
    void unknownToolIdsFailExplicitly() {
        assertThatThrownBy(() -> ToolCatalogue.canonical().requireTool("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown tool: unknown");
    }

    @Test
    void descriptorsExposeTheirExactSafetyAnnotations() {
        ToolDescriptor list = ToolCatalogue.canonical().requireTool("swarm_list");
        ToolDescriptor remove = ToolCatalogue.canonical().requireTool("swarm_remove");

        assertThat(list.readOnly()).isTrue();
        assertThat(list.destructive()).isFalse();
        assertThat(list.idempotent()).isTrue();
        assertThat(remove.readOnly()).isFalse();
        assertThat(remove.destructive()).isTrue();
        assertThat(remove.idempotent()).isTrue();
    }

    @Test
    void missingRequiredSha256ProviderFailsExplicitly() {
        ToolCatalogue.canonical();
        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256"))
                .thenThrow(new NoSuchAlgorithmException("missing"));

            assertThatThrownBy(() -> {
                var method = ToolCatalogue.class.getDeclaredMethod("sha256", String.class);
                method.setAccessible(true);
                try {
                    method.invoke(null, "content");
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }).isInstanceOf(IllegalStateException.class)
                .hasMessage("SHA-256 is required by Java");
        }
    }
}
