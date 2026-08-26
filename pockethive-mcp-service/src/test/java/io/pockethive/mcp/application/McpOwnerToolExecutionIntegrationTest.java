package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpOwnerToolExecutionIntegrationTest {
    private final OwnerApiPort owners = mock(OwnerApiPort.class);
    private final SwarmReadinessObserver readiness = mock(SwarmReadinessObserver.class);
    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    private final ToolCatalogue catalogue = ToolCatalogue.canonical();
    private final McpToolExecutor executor = new McpToolExecutor(
        new ScenarioManagerToolExecutor(owners),
        new OrchestratorToolExecutor(owners, readiness),
        mock(QaWorkflowToolExecutor.class));

    @BeforeEach
    void authenticateCaller() {
        when(exchange.transportContext()).thenReturn(McpTransportContext.create(Map.of(
            "pockethive.issuer", "https://issuer.example",
            "pockethive.subject", "qa-lead",
            "pockethive.principalLabel", "qa-lead",
            "pockethive.clientId", "test-client",
            "pockethive.scopes", String.join(" ", Set.of(
                PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ,
                PocketHiveMcpScopes.OPERATE, PocketHiveMcpScopes.CLEANUP)))));
    }

    @Test
    void mapsEveryScenarioManagerAndSwarmLifecycleToolToItsCanonicalOwnerCall() {
        Map<String, Object> input = ownerInput();
        Object marker = Map.of("marker", true);
        when(owners.get(any())).thenReturn(marker);
        when(owners.getText(any())).thenReturn("preview-text");
        when(owners.post(any(), any())).thenReturn(marker);
        when(owners.delete(any())).thenReturn(marker);
        when(readiness.observe(any(), any()))
            .thenReturn(new SwarmReadinessResult(true, "swarm/a", Map.of(), "READY", 1));

        assertOwnerGet("scenario_list", Map.of(), "/scenario-manager/scenarios");
        assertOwnerGet("scenario_get", input, "/scenario-manager/scenarios/scenario%2Fa");
        assertOwnerGetText("scenario_raw_read", input, "/scenario-manager/scenarios/scenario%2Fa/raw");
        assertOwnerGetText("scenario_schema_read", input,
            "/scenario-manager/scenarios/scenario%2Fa/schema?path=schema%20a.json");
        assertOwnerGetText("scenario_template_read", input,
            "/scenario-manager/scenarios/scenario%2Fa/template?path=schema%20a.json");
        assertOwnerGet("scenario_bundle_tree_read", input,
            "/scenario-manager/scenarios/bundles/tree?bundleKey=bundle/a");
        assertOwnerGet("scenario_bundle_file_read", input,
            "/scenario-manager/scenarios/bundles/file?bundleKey=bundle/a&path=schema%20a.json");
        Map<String, Object> nestedBundleInput = new LinkedHashMap<>(input);
        nestedBundleInput.put("path", "templates/http/request.yaml");
        assertOwnerGet("scenario_bundle_file_read", nestedBundleInput,
            "/scenario-manager/scenarios/bundles/file?bundleKey=bundle/a&path=templates/http/request.yaml");
        assertOwnerGet("scenario_suts_list", input, "/scenario-manager/scenarios/scenario%2Fa/suts");
        assertOwnerGet("scenario_sut_get", input,
            "/scenario-manager/scenarios/scenario%2Fa/suts/sut-a");
        assertOwnerGet("scenario_capabilities_get", Map.of(),
            "/scenario-manager/api/capabilities?all=true");
        assertOwnerGet("scenario_templates_catalog", input, "/scenario-manager/api/templates");
        assertOwnerGet("swarm_list", input, "/orchestrator/api/swarms");
        assertOwnerGet("swarm_get", input, "/orchestrator/api/swarms/swarm%2Fa");

        execute("swarm_wait_ready", input);
        verify(readiness).observe("/orchestrator/api/swarms/swarm%2Fa", "swarm/a");
        clearInvocations(readiness);

        execute("swarm_create", input);
        Map<String, Object> expectedCreate = new LinkedHashMap<>();
        expectedCreate.put("templateId", "template-a");
        expectedCreate.put("idempotencyKey", "idem-a");
        expectedCreate.put("autoPullImages", true);
        expectedCreate.put("sutId", "sut-a");
        expectedCreate.put("variablesProfileId", "vars-a");
        expectedCreate.put("networkMode", "PROXIED");
        expectedCreate.put("networkProfileId", "proxy-a");
        verify(owners).post("/orchestrator/api/swarms/swarm%2Fa/create", expectedCreate);
        clearInvocations(owners);
        assertLifecycle("swarm_start", "start", input, marker);
        assertLifecycle("swarm_stop", "stop", input, marker);
        assertLifecycle("swarm_remove", "remove", input, marker);

        assertOwnerGet("debug_journal", input,
            "/orchestrator/api/swarms/swarm%2Fa/journal/page?limit=25&runId=run%2Fa&severity=WARN");
        assertOwnerGet("debug_journal_runs", input,
            "/orchestrator/api/swarms/swarm%2Fa/journal/runs");
        assertOwnerGet("debug_hive_journal", input, "/orchestrator/api/journal/hive/page?limit=25");
        assertOwnerPost("debug_tap", input, "/orchestrator/api/debug/taps", input);
        assertOwnerGet("debug_tap_read", input, "/orchestrator/api/debug/taps/tap%2Fa?drain=3");
        execute("debug_tap_close", input);
        verify(owners).delete("/orchestrator/api/debug/taps/tap%2Fa");
    }

    @Test
    void swarmCreateRejectsMissingNullablePropertiesAndUnknownNetworkModesExplicitly() {
        Map<String, Object> missingNullableProperty = ownerInput();
        missingNullableProperty.remove("sutId");
        assertCode("TOOL_INPUT_REQUIRED", () -> execute("swarm_create", missingNullableProperty));

        Map<String, Object> unknownNetworkMode = ownerInput();
        unknownNetworkMode.put("networkMode", "AUTOMATIC");
        assertCode("TOOL_INPUT_INVALID", () -> execute("swarm_create", unknownNetworkMode));
        verify(owners, never()).post(any(), any());
    }

    @Test
    void mapsConfigurationDiagnosticsAndAggregateToolsWithoutInventingTargets() {
        Map<String, Object> input = ownerInput();
        when(owners.get(any())).thenAnswer(invocation -> Map.of("get", invocation.getArgument(0)));
        when(owners.post(any(), any())).thenAnswer(invocation -> Map.of("post", invocation.getArgument(0)));

        assertOwnerPost("component_config_preview", input,
            "/orchestrator/api/components/generator/gen%2Fa/config/preview",
            Map.of("swarmId", "swarm/a", "patch", Map.of("rate", 5)));
        assertOwnerPost("component_config_update", input,
            "/orchestrator/api/components/generator/gen%2Fa/config",
            Map.of("swarmId", "swarm/a", "patch", Map.of("rate", 5), "idempotencyKey", "idem-a"));
        Map<String, Object> cleanupPlanBody = Map.of(
            "swarmId", "swarm/a", "runId", "run/a", "includeRunning", false, "includeRabbit", true);
        assertOwnerPost("runtime_cleanup_plan", input,
            "/orchestrator/api/runtime/cleanup/plan", cleanupPlanBody);
        Map<String, Object> cleanupExecuteBody = new LinkedHashMap<>(cleanupPlanBody);
        cleanupExecuteBody.put("candidateSetHash", "sha256:" + "a".repeat(64));
        cleanupExecuteBody.put("candidateIds", List.of("runtime-a"));
        cleanupExecuteBody.put("idempotencyKey", "idem-a");
        cleanupExecuteBody.put("reason", "reviewed cleanup");
        assertOwnerPost("runtime_cleanup_execute", input,
            "/orchestrator/api/runtime/cleanup/execute", cleanupExecuteBody);
        assertOwnerPost("runtime_tail_worker_logs", input,
            "/orchestrator/api/runtime/debug/resources/logs", input);
        assertOwnerPost("runtime_get_worker_version", input,
            "/orchestrator/api/runtime/debug/resources/version", input);
        assertOwnerPost("runtime_list_workers", input,
            "/orchestrator/api/runtime/debug/resources/list", input);
        assertOwnerPost("runtime_inspect_worker", input,
            "/orchestrator/api/runtime/debug/resources/inspect", input);
        assertOwnerPost("runtime_rabbit_topology_snapshot", input,
            "/orchestrator/api/runtime/debug/rabbit/topology", input);

        Map<String, Object> assessmentBody = Map.of("swarmId", "swarm/a", "runId", "run/a");
        for (String toolId : List.of(
            "runtime_assess_swarm", "runtime_diff_swarm_runtime",
            "runtime_control_plane_status", "runtime_manifest_validate")) {
            assertOwnerPost(toolId, input,
                "/orchestrator/api/runtime/debug/assessment", assessmentBody);
        }
        assertThat(execute("runtime_swarm_timeline", input)).isEqualTo(Map.of(
            "get", "/orchestrator/api/swarms/swarm%2Fa/journal/page?limit=25"));
    }

    @Test
    void appliesOnlyThePublishedMcpJournalLimitsWhenTheCallerOmitsThem() {
        when(owners.get(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(execute("debug_journal", Map.of("swarmId", "swarm/a")))
            .isEqualTo("/orchestrator/api/swarms/swarm%2Fa/journal/page?limit=50");
        assertThat(execute("debug_hive_journal", Map.of()))
            .isEqualTo("/orchestrator/api/journal/hive/page?limit=50");
        assertThat(execute("runtime_swarm_timeline", Map.of("swarmId", "swarm/a")))
            .isEqualTo("/orchestrator/api/swarms/swarm%2Fa/journal/page?limit=100");
    }

    @Test
    void combinesAllScenarioContractOwnerReads() {
        when(owners.get(any())).thenAnswer(invocation -> invocation.getArgument(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) execute("scenario_contracts_get", Map.of());

        assertThat(result).containsEntry("authoringContract", "/scenario-manager/api/authoring-contract")
            .containsEntry("fingerprint", "/scenario-manager/api/authoring-contract/fingerprint")
            .doesNotContainKeys("capabilities", "templates");
    }

    @Test
    void mapsExactScenarioCapabilitySelectorsAndRejectsAmbiguity() {
        when(owners.get(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(execute("scenario_capabilities_get", Map.of()))
            .isEqualTo("/scenario-manager/api/capabilities?all=true");
        assertThat(execute("scenario_capabilities_get", Map.of("all", true)))
            .isEqualTo("/scenario-manager/api/capabilities?all=true");
        assertThat(execute("scenario_capabilities_get", Map.of("imageName", "request builder")))
            .isEqualTo("/scenario-manager/api/capabilities?imageName=request%20builder");
        assertThat(execute("scenario_capabilities_get", Map.of("imageDigest", "sha256:abc")))
            .isEqualTo("/scenario-manager/api/capabilities?imageDigest=sha256:abc");
        assertCode("TOOL_INPUT_INVALID", () -> execute("scenario_capabilities_get",
            Map.of("unexpected", true)));
        assertCode("TOOL_INPUT_INVALID", () -> execute("scenario_capabilities_get", Map.of("all", "true")));
        assertCode("TOOL_INPUT_INVALID", () -> execute("scenario_capabilities_get", Map.of("all", false)));
        assertCode("TOOL_INPUT_INVALID", () -> execute("scenario_capabilities_get",
            Map.of("all", true, "imageName", "processor")));
    }

    private Object execute(String toolId, Map<String, Object> input) {
        return executor.execute(catalogue.requireTool(toolId), exchange, input);
    }

    private void assertOwnerGet(String toolId, Map<String, Object> input, String path) {
        execute(toolId, input);
        verify(owners).get(path);
        clearInvocations(owners);
    }

    private void assertOwnerGetText(String toolId, Map<String, Object> input, String path) {
        assertThat(execute(toolId, input)).isEqualTo("preview-text");
        verify(owners).getText(path);
        clearInvocations(owners);
    }

    private void assertOwnerPost(String toolId, Map<String, Object> input, String path, Object body) {
        execute(toolId, input);
        verify(owners).post(path, body);
        clearInvocations(owners);
    }

    private void assertLifecycle(String toolId, String action, Map<String, Object> input, Object expected) {
        assertThat(execute(toolId, input)).isEqualTo(expected);
        verify(owners).post("/orchestrator/api/swarms/swarm%2Fa/" + action,
            Map.of("idempotencyKey", "idem-a"));
        clearInvocations(owners);
    }

    private static void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ToolExecutionException.class)
            .extracting(exception -> ((ToolExecutionException) exception).code())
            .isEqualTo(code);
    }

    private static Map<String, Object> ownerInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scenario/a");
        input.put("bundleKey", "bundle/a");
        input.put("path", "schema a.json");
        input.put("swarmId", "swarm/a");
        input.put("templateId", "template-a");
        input.put("autoPullImages", true);
        input.put("sutId", "sut-a");
        input.put("variablesProfileId", "vars-a");
        input.put("networkMode", "PROXIED");
        input.put("networkProfileId", "proxy-a");
        input.put("idempotencyKey", "idem-a");
        input.put("limit", 25);
        input.put("runId", "run/a");
        input.put("severity", "WARN");
        input.put("tapId", "tap/a");
        input.put("drain", 3);
        input.put("role", "generator");
        input.put("direction", "IN");
        input.put("ioName", "in");
        input.put("maxItems", 5);
        input.put("ttlSeconds", 60);
        input.put("instanceId", "gen/a");
        input.put("patch", Map.of("rate", 5));
        input.put("runtimeId", "runtime-a");
        input.put("candidateSetHash", "sha256:" + "a".repeat(64));
        input.put("candidateIds", List.of("runtime-a"));
        input.put("reason", "reviewed cleanup");
        input.put("includeRunning", false);
        input.put("includeRabbit", true);
        return input;
    }
}
