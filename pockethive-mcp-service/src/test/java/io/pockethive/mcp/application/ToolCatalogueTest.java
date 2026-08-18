package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
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
    void everyPublishedToolHasOneCanonicalIdContractAndConnectedSkill() {
        ToolCatalogue catalogue = ToolCatalogue.canonical();

        assertThat(catalogue.tools()).hasSize(49);
        assertThat(catalogue.tools()).allSatisfy(tool -> {
            assertThat(tool.id()).matches("[a-z][a-z0-9_]*");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).containsEntry("type", "object");
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
        assertThat(catalogue.skills().values()).allSatisfy(skill -> {
            assertThat(skill.version()).isEqualTo("1.0.0");
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
    }

    @Test
    void highRiskAndBoundedInputsHaveExactMachineReadableConstraints() {
        ToolCatalogue catalogue = ToolCatalogue.canonical();

        Map<String, Object> readiness = catalogue.requireTool("swarm_wait_ready").inputSchema();
        assertThat(readiness.get("required")).isEqualTo(List.of("swarmId"));
        Map<String, Object> readinessProperties = properties(readiness);
        assertThat(readinessProperties).doesNotContainKey("timeoutSec");

        Map<String, Object> publication = catalogue.requireTool("scenario_bundle_publication_prepare").inputSchema();
        assertThat(property(publication, "mode").get("enum")).isEqualTo(List.of("CREATE", "REPLACE"));
        assertThat(property(publication, "archiveDigest").get("pattern"))
            .isEqualTo("^sha256:[0-9a-f]{64}$");

        Map<String, Object> validation = catalogue.requireTool("scenario_bundle_validation_prepare").inputSchema();
        assertThat(property(validation, "source")).containsEntry("type", "object");
        assertThat(property(validation, "fileManifest")).containsEntry("type", "array");
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
