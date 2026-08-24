package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.QaRequirementTopic;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

class McpToolExecutorTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final PrincipalKey PRINCIPAL = new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
    private static final String SHA = "sha256:" + "a".repeat(64);
    private static final String COMMIT = "a".repeat(40);

    private final OwnerApiPort owners = mock(OwnerApiPort.class);
    private final BundleUploadCoordinator uploads = mock(BundleUploadCoordinator.class);
    private final MemoryState state = new MemoryState();
    private final SwarmReadinessObserver readiness = mock(SwarmReadinessObserver.class);
    private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
    private final ToolCatalogue catalogue = ToolCatalogue.canonical();
    private final McpToolExecutor executor = new McpToolExecutor(
        owners, properties(), new ObjectMapper().findAndRegisterModules(), uploads, state, readiness,
        Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void authenticateCaller() {
        when(exchange.transportContext()).thenReturn(McpTransportContext.create(Map.of(
            "pockethive.issuer", PRINCIPAL.issuer().toString(),
            "pockethive.subject", PRINCIPAL.subject(),
            "pockethive.principalLabel", "qa-lead",
            "pockethive.clientId", "test-client",
            "pockethive.scopes", String.join(" ", Set.of(
                PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ, PocketHiveMcpScopes.OPERATE,
                PocketHiveMcpScopes.AUTHOR, PocketHiveMcpScopes.PUBLISH, PocketHiveMcpScopes.CLEANUP)))));
        when(exchange.getClientCapabilities()).thenReturn(new McpSchema.ClientCapabilities(
            null, null, null, new McpSchema.ClientCapabilities.Elicitation(
                new McpSchema.ClientCapabilities.Elicitation.Form(), null)));
        when(exchange.getClientInfo()).thenReturn(new McpSchema.Implementation("test-client", "1.0.0"));
    }

    @Test
    void mapsEveryScenarioManagerAndSwarmLifecycleToolToItsCanonicalOwnerCall() {
        Map<String, Object> input = ownerInput();
        Object marker = Map.of("marker", true);
        when(owners.get(any())).thenReturn(marker);
        when(owners.getText(any())).thenReturn("preview-text");
        when(owners.post(any(), any())).thenReturn(marker);
        when(owners.delete(any())).thenReturn(marker);
        when(readiness.observe(any(), any())).thenReturn(new SwarmReadinessResult(true, "swarm/a", Map.of(), "READY", 1));

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
        assertOwnerGet("scenario_suts_list", input,
            "/scenario-manager/scenarios/scenario%2Fa/suts");
        assertOwnerGet("scenario_sut_get", input,
            "/scenario-manager/scenarios/scenario%2Fa/suts/sut-a");
        assertOwnerGet("scenario_capabilities_get", input, "/scenario-manager/api/capabilities?all=true");
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
        expectedCreate.put("autoPullImages", false);
        expectedCreate.put("sutId", "sut-a");
        expectedCreate.put("variablesProfileId", "vars-a");
        expectedCreate.put("networkMode", "DIRECT");
        expectedCreate.put("networkProfileId", null);
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
        assertOwnerGet("debug_tap_read", input, "/orchestrator/api/debug/taps/tap%2Fa?drain=true");
        execute("debug_tap_close", input);
        verify(owners).delete("/orchestrator/api/debug/taps/tap%2Fa");
    }

    @Test
    void mapsConfigurationDiagnosticsAndAggregateToolsWithoutInventingTargets() {
        Map<String, Object> input = ownerInput();
        when(owners.get(any())).thenAnswer(invocation -> Map.of("get", invocation.getArgument(0)));
        when(owners.post(any(), any())).thenAnswer(invocation -> Map.of("post", invocation.getArgument(0)));

        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) execute("component_config_preview", input);
        assertThat(preview).containsEntry("sideEffect", "no-config-write")
            .containsEntry("patch", input.get("patch"));
        assertThat(preview.get("target")).isEqualTo(Map.of(
            "swarmId", "swarm/a", "role", "generator", "instanceId", "gen/a"));
        verify(owners).get("/orchestrator/api/swarms/swarm%2Fa");
        clearInvocations(owners);

        assertOwnerPost("component_config_update", input,
            "/orchestrator/api/components/generator/gen%2Fa/config", input);
        assertOwnerPost("runtime_cleanup_plan", input, "/orchestrator/api/runtime/cleanup/plan", input);
        assertOwnerPost("runtime_cleanup_execute", input, "/orchestrator/api/runtime/cleanup/execute", input);
        assertOwnerPost("runtime_tail_worker_logs", input, "/orchestrator/api/runtime/debug/resources/logs", input);
        assertOwnerPost("runtime_get_worker_version", input, "/orchestrator/api/runtime/debug/resources/version", input);
        assertOwnerPost("runtime_list_workers", input, "/orchestrator/api/runtime/debug/resources/list", input);
        assertOwnerPost("runtime_inspect_worker", input, "/orchestrator/api/runtime/debug/resources/inspect", input);
        assertOwnerPost("runtime_rabbit_topology_snapshot", input,
            "/orchestrator/api/runtime/debug/rabbit/topology", input);

        @SuppressWarnings("unchecked")
        Map<String, Object> diff = (Map<String, Object>) execute("runtime_diff_swarm_runtime", input);
        assertThat(diff).containsOnlyKeys("swarm", "resources", "rabbitTopology");
        verifyAggregateCalls(input, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) execute("runtime_control_plane_status", input);
        assertThat(status).containsOnlyKeys("swarm", "rabbitTopology");
        verifyAggregateCalls(input, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) execute("runtime_manifest_validate", input);
        assertThat(manifest).containsOnlyKeys("swarm", "resources", "rabbitTopology");
        verifyAggregateCalls(input, true);

        Object timeline = execute("runtime_swarm_timeline", input);
        assertThat(timeline).isEqualTo(Map.of(
            "get", "/orchestrator/api/swarms/swarm%2Fa/journal/page?limit=25"));
    }

    @Test
    void combinesAllScenarioContractOwnerReads() {
        when(owners.get(any())).thenAnswer(invocation -> invocation.getArgument(0));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) execute("scenario_contracts_get", Map.of());

        assertThat(result).containsEntry("authoringContract", "/scenario-manager/api/authoring-contract")
            .containsEntry("fingerprint", "/scenario-manager/api/authoring-contract/fingerprint")
            .containsEntry("capabilities", "/scenario-manager/api/capabilities?all=true")
            .containsEntry("templates", "/scenario-manager/api/templates");
    }

    @Test
    void runsTheNoInferenceWorkflowAndInvalidatesGeneratedFilesAfterCancellation() {
        Object created = execute("agent_session_create", Map.of());
        String sessionId = textField(created, "agentSessionId");
        assertThat(sessionId).startsWith("as-");
        assertThat(state.maintainedAt).isEqualTo(NOW);

        Object workflowCreated = execute("scenario_workflow_create", Map.of(
            "agentSessionId", sessionId, "expectedSessionRevision", 0));
        String workflowId = textField(workflowCreated, "workflowId");
        assertThat(textField(execute("scenario_workflow_get", Map.of("workflowId", workflowId)), "workflowId"))
            .isEqualTo(workflowId);

        when(exchange.createElicitation(any())).thenReturn(new McpSchema.ElicitResult(
            McpSchema.ElicitResult.Action.DECLINE, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> declined = (Map<String, Object>) execute("scenario_workflow_answer", Map.of(
            "workflowId", workflowId, "expectedRevision", 0, "topic", "goal_and_risk"));
        assertThat(declined).containsEntry("disposition", "UNKNOWN")
            .containsEntry("elicitationAction", McpSchema.ElicitResult.Action.DECLINE);
        assertThat(state.workflows.get(workflowId).revision()).isZero();

        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            when(exchange.createElicitation(any())).thenReturn(accepted("NOT_APPLICABLE", "Not required: " + topic));
            execute("scenario_workflow_answer", Map.of(
                "workflowId", workflowId,
                "expectedRevision", state.workflows.get(workflowId).revision(),
                "topic", topic.name()));
        }
        ArgumentCaptor<McpSchema.ElicitRequest> elicitation = ArgumentCaptor.forClass(McpSchema.ElicitRequest.class);
        verify(exchange, org.mockito.Mockito.atLeastOnce()).createElicitation(elicitation.capture());
        assertThat(elicitation.getAllValues()).allSatisfy(request -> {
            assertThat(request.message()).isNotBlank();
            assertThat(request.mode()).isEqualTo("form");
        });
        when(owners.get("/scenario-manager/api/authoring-contract/fingerprint"))
            .thenReturn(Map.of("fingerprint", "cap-a"));

        @SuppressWarnings("unchecked")
        Map<String, Object> generated = (Map<String, Object>) execute("scenario_workflow_generate", Map.of(
            "workflowId", workflowId,
            "expectedRevision", state.workflows.get(workflowId).revision(),
            "files", List.of(
                Map.of("path", "z/setup.sh", "content", "#!/bin/sh\ntrue\n"),
                Map.of("path", "scenario.yaml", "content", "id: sample\n"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) generated.get("files");
        assertThat(files).extracting(file -> file.get("path"))
            .containsExactly("scenario.yaml", "z/setup.sh");
        assertThat(files).allSatisfy(file -> assertThat(file.get("sha256")).asString()
            .matches("sha256:[0-9a-f]{64}"));
        assertThat(state.workflows.get(workflowId).state().name()).isEqualTo("GENERATED");
        assertThat(state.savedWorkflowsWithFiles).contains(workflowId);
        assertThat(generated.get("fileSetDigest")).isNotEqualTo("sha256:" +
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        Object cancelled = execute("scenario_workflow_cancel", Map.of(
            "workflowId", workflowId, "expectedRevision", state.workflows.get(workflowId).revision()));
        assertThat(cancelled).isNotNull();
        assertThat(state.workflows.get(workflowId).state().name()).isEqualTo("CANCELLED");
        assertThat(state.generatedFiles).doesNotContainKey(workflowId);
        assertThat(state.removedGeneratedFiles).contains(workflowId);

        @SuppressWarnings("unchecked")
        Map<String, Object> listed = (Map<String, Object>) execute(
            "agent_session_list_workflows", Map.of("agentSessionId", sessionId));
        assertThat(listed).containsEntry("count", 1);
        assertThat(execute("scenario_workflow_list", Map.of("agentSessionId", sessionId))).isEqualTo(listed);

        Object closed = execute("agent_session_close", Map.of("agentSessionId", sessionId, "expectedRevision", "1"));
        assertThat(closed).isNotNull();
        assertThat(state.savedSessions).contains(sessionId);
        assertThat(textField(execute("agent_session_get", Map.of("agentSessionId", sessionId)), "state"))
            .isEqualTo("CLOSED");
    }

    @Test
    void recordsEachAcceptedAnswerDispositionWithExplicitSourceRules() {
        ScenarioWorkflow workflow = workflow("wf-answer");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-answer", AgentSession.open("as-answer", PRINCIPAL, NOW, Duration.ofHours(1)));

        when(exchange.createElicitation(any())).thenReturn(accepted("USER_PROVIDED", "Explicit goal"));
        Object firstAnswer = execute("scenario_workflow_answer", Map.of(
            "workflowId", workflow.id(), "expectedRevision", 0, "topic", "GOAL_AND_RISK"));
        assertThat(firstAnswer).isNotNull();
        assertThat(workflow.requirements().get(QaRequirementTopic.GOAL_AND_RISK).value()).isEqualTo("Explicit goal");
        assertThat(state.removedGeneratedFiles).contains(workflow.id());

        when(exchange.createElicitation(any())).thenReturn(new McpSchema.ElicitResult(
            McpSchema.ElicitResult.Action.ACCEPT,
            Map.of("disposition", "USER_CONFIRMED_SOURCE", "answer", "Use the contract",
                "sourceName", "openapi.yaml", "sourceDigest", SHA)));
        execute("scenario_workflow_answer", Map.of(
            "workflowId", workflow.id(), "expectedRevision", 1,
            "topic", "JOURNEYS_SCHEMAS_AND_EXPECTATIONS"));
        assertThat(workflow.requirements().get(QaRequirementTopic.JOURNEYS_SCHEMAS_AND_EXPECTATIONS)
            .confirmedSource().digest()).isEqualTo(SHA);
        assertThat(workflow.requirements().get(QaRequirementTopic.JOURNEYS_SCHEMAS_AND_EXPECTATIONS)
            .provenance().requestedSchemaDigest()).isNotEqualTo("sha256:" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void preparesWorkflowAndDirectValidationWithExactManifestAndSource() {
        ScenarioWorkflow workflow = generatedWorkflow("wf-generated", "as-generated");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-generated", AgentSession.open("as-generated", PRINCIPAL, NOW, Duration.ofHours(1)));
        Map<String, Object> input = bundleInput();
        input.put("workflowId", workflow.id());
        input.put("expectedRevision", workflow.revision());
        ValidationUploadTicket ticket = mock(ValidationUploadTicket.class);
        when(ticket.id()).thenReturn("uv-test");
        when(ticket.expiresAt()).thenReturn(NOW.plusSeconds(300));
        when(uploads.prepareValidation(eq(PRINCIPAL), eq(workflow.id()), any(), any(), eq(NOW)))
            .thenReturn(ticket);

        assertThat(json(execute("scenario_bundle_validation_prepare", input)))
            .contains("\"ticketId\":\"uv-test\"")
            .contains("\"uploadUrl\":\"http://127.0.0.1:8080/mcp/uploads/uv-test\"")
            .contains("\"expiresAt\":");
        ArgumentCaptor<io.pockethive.mcp.domain.BundleFileManifest> manifest =
            ArgumentCaptor.forClass(io.pockethive.mcp.domain.BundleFileManifest.class);
        verify(uploads).prepareValidation(eq(PRINCIPAL), eq(workflow.id()),
            eq(new SourceMetadata("git@example/repo", COMMIT, "scenarios/sample", SourceVerification.CLIENT_ASSERTED)),
            manifest.capture(), eq(NOW));
        assertThat(manifest.getValue().files()).hasSize(1);
        assertThat(manifest.getValue().files().getFirst().byteCount()).isEqualTo(4);

        ValidationUploadTicket direct = mock(ValidationUploadTicket.class);
        when(direct.id()).thenReturn("uv-direct");
        when(direct.expiresAt()).thenReturn(NOW.plusSeconds(300));
        when(uploads.prepareDirectValidation(eq(PRINCIPAL), any(), any(), eq(NOW))).thenReturn(direct);
        assertThat(json(execute("scenario_bundle_direct_validation_prepare", bundleInput())))
            .contains("\"ticketId\":\"uv-direct\"");
        verify(uploads).prepareDirectValidation(eq(PRINCIPAL), any(), any(), eq(NOW));
    }

    @Test
    void readsReceiptsAttemptsAndPreparesBothPublicationModes() {
        BundleValidationReceipt directReceipt = receipt(UploadWorkflowBinding.direct());
        when(uploads.validationReceipt("receipt-a", PRINCIPAL)).thenReturn(directReceipt);
        Object receiptView = execute("scenario_bundle_validation_receipt_get", Map.of("receiptId", "receipt-a"));
        assertThat(json(receiptView)).contains("\"receiptId\":\"receipt-a\"")
            .contains("\"bundleContentDigest\":\"" + SHA + "\"")
            .contains("\"scenarioId\":\"scenario-a\"")
            .contains("\"scenarioName\":\"Scenario A\"")
            .doesNotContain("qa-lead", "issuer.example");

        PublicationUploadTicket publication = mock(PublicationUploadTicket.class);
        when(publication.id()).thenReturn("up-test");
        when(publication.expiresAt()).thenReturn(NOW.plusSeconds(300));
        when(publication.attemptId()).thenReturn("attempt-a");
        when(publication.validationReceiptId()).thenReturn("receipt-a");
        when(publication.mode()).thenReturn(PublicationMode.CREATE);
        when(uploads.preparePublication(eq(PRINCIPAL), eq("receipt-a"), eq(PublicationMode.CREATE),
            eq(null), any(), any(), eq(SHA), eq(SHA), eq(NOW))).thenReturn(publication);
        Map<String, Object> create = bundleInput();
        create.put("validationReceiptId", "receipt-a");
        create.put("mode", "create");
        create.put("archiveDigest", SHA);
        create.put("bundleContentDigest", SHA);
        assertThat(json(execute("scenario_bundle_publication_prepare", create)))
            .contains("\"ticketId\":\"up-test\"")
            .contains("\"attemptId\":\"attempt-a\"")
            .contains("\"mode\":\"CREATE\"");

        when(uploads.preparePublication(eq(PRINCIPAL), eq("receipt-a"), eq(PublicationMode.REPLACE),
            eq("existing"), any(), any(), eq(SHA), eq(SHA), eq(NOW))).thenReturn(publication);
        Map<String, Object> replace = new LinkedHashMap<>(create);
        replace.put("mode", "REPLACE");
        replace.put("scenarioId", "existing");
        when(publication.mode()).thenReturn(PublicationMode.REPLACE);
        when(publication.scenarioId()).thenReturn("existing");
        assertThat(json(execute("scenario_bundle_publication_prepare", replace)))
            .contains("\"mode\":\"REPLACE\"", "\"scenarioId\":\"existing\"");

        PublicationAttempt attempt = mock(PublicationAttempt.class);
        when(attempt.id()).thenReturn("attempt-a");
        when(attempt.mode()).thenReturn(PublicationMode.CREATE);
        when(attempt.scenarioId()).thenReturn("scenario-a");
        when(attempt.expectedContentDigest()).thenReturn(SHA);
        when(attempt.createdAt()).thenReturn(NOW);
        when(attempt.state()).thenReturn(PublicationAttemptState.AMBIGUOUS);
        when(uploads.publicationAttempt("attempt-a", PRINCIPAL)).thenReturn(attempt);
        when(uploads.reconcile("attempt-a", PRINCIPAL)).thenReturn(attempt);
        assertThat(json(execute("scenario_bundle_publication_attempt_get", Map.of("attemptId", "attempt-a"))))
            .contains("\"attemptId\":\"attempt-a\"", "\"state\":\"AMBIGUOUS\"")
            .doesNotContain("qa-lead", "issuer.example");
        assertThat(json(execute("scenario_bundle_publication_reconcile", Map.of("attemptId", "attempt-a"))))
            .contains("\"attemptId\":\"attempt-a\"");
    }

    @Test
    void requiresTheReceiptWorkflowOnlyForWorkflowBoundPublication() {
        ScenarioWorkflow workflow = workflow("wf-bound");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-answer", AgentSession.open("as-answer", PRINCIPAL, NOW, Duration.ofHours(1)));
        when(uploads.validationReceipt("receipt-a", PRINCIPAL))
            .thenReturn(receipt(UploadWorkflowBinding.workflow(workflow.id())));
        when(uploads.preparePublication(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(mock(PublicationUploadTicket.class));
        Map<String, Object> input = bundleInput();
        input.put("validationReceiptId", "receipt-a");
        input.put("mode", "CREATE");
        input.put("archiveDigest", SHA);
        input.put("bundleContentDigest", SHA);

        execute("scenario_bundle_publication_prepare", input);

        assertThat(state.findWorkflowCalls).contains(workflow.id());
    }

    @Test
    void rejectsMissingCapabilitiesScopesUnknownHandlersAndMalformedInputExplicitly() {
        when(exchange.getClientCapabilities()).thenReturn(null);
        assertCode("ELICITATION_CAPABILITY_REQUIRED", () -> execute("agent_session_create", Map.of()));

        when(exchange.transportContext()).thenReturn(McpTransportContext.create(Map.of(
            "pockethive.issuer", PRINCIPAL.issuer().toString(), "pockethive.subject", PRINCIPAL.subject(),
            "pockethive.principalLabel", "qa-lead",
            "pockethive.clientId", "test-client", "pockethive.scopes", PocketHiveMcpScopes.READ)));
        assertCode("MCP_SCOPE_REQUIRED", () -> execute("swarm_start", ownerInput()));

        authenticateCaller();
        ToolDescriptor missing = new ToolDescriptor("missing", "missing", Map.of("type", "object"),
            ToolOwner.MCP, PocketHiveMcpScopes.READ, true, false, true, List.of("pockethive-orientation"));
        assertCode("TOOL_HANDLER_MISSING", () -> executor.execute(missing, exchange, Map.of()));
        ToolDescriptor ownerMissing = new ToolDescriptor("missing", "missing", Map.of("type", "object"),
            ToolOwner.ORCHESTRATOR, PocketHiveMcpScopes.READ, true, false, true, List.of("pockethive-orientation"));
        assertCode("TOOL_HANDLER_MISSING", () -> executor.execute(ownerMissing, exchange, Map.of()));

        assertCode("TOOL_INPUT_REQUIRED", () -> execute("scenario_get", Map.of()));
        assertCode("TOOL_INPUT_REQUIRED", () -> execute("scenario_get", Map.of("scenarioId", " ")));
        Object session = execute("agent_session_create", Map.of());
        assertCode("TOOL_INPUT_INVALID", () -> execute("scenario_workflow_create", Map.of(
            "agentSessionId", textField(session, "agentSessionId"), "expectedSessionRevision", "many")));
        assertCode("SOURCE_METADATA_INVALID", () -> execute("scenario_bundle_direct_validation_prepare",
            Map.of("source", "not-an-object", "fileManifest", manifestInput())));
        Map<String, Object> invalidSource = bundleInput();
        invalidSource.put("source", Map.of("repository", "repo", "commit", "abc", "bundlePath", "path",
            "verification", "VERIFIED_BY_MAGIC"));
        assertCode("SOURCE_METADATA_INVALID",
            () -> execute("scenario_bundle_direct_validation_prepare", invalidSource));
        Map<String, Object> invalidManifest = bundleInput();
        invalidManifest.put("fileManifest", "not-an-array");
        assertCode("BUNDLE_MANIFEST_INVALID",
            () -> execute("scenario_bundle_direct_validation_prepare", invalidManifest));
        Map<String, Object> invalidEntry = bundleInput();
        invalidEntry.put("fileManifest", List.of("not-an-object"));
        assertCode("BUNDLE_MANIFEST_INVALID",
            () -> execute("scenario_bundle_direct_validation_prepare", invalidEntry));
    }

    @Test
    void rejectsUnsafeGeneratedFilesAndInvalidElicitationSourceCombinations() {
        ScenarioWorkflow workflow = fullyAnsweredWorkflow("wf-files", "as-files");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-files", AgentSession.open("as-files", PRINCIPAL, NOW, Duration.ofHours(1)));

        for (Object files : List.of(
            List.of(),
            List.of("not-an-object"),
            List.of(Map.of("path", "scenario.yaml", "content", 42)),
            List.of(Map.of("path", "/absolute", "content", "x")),
            List.of(Map.of("path", "../escape", "content", "x")),
            List.of(Map.of("path", " scenario.yaml", "content", "x")),
            List.of(Map.of("path", "scenario.yaml ", "content", "x")),
            List.of(Map.of("path", "bad\\path", "content", "x")))) {
            assertThatThrownBy(() -> execute("scenario_workflow_generate", Map.of(
                "workflowId", workflow.id(), "expectedRevision", workflow.revision(), "files", files)))
                .isInstanceOf(ToolExecutionException.class);
        }

        ScenarioWorkflow answer = workflow("wf-source-fields");
        state.workflows.put(answer.id(), answer);
        state.sessions.put("as-answer", AgentSession.open("as-answer", PRINCIPAL, NOW, Duration.ofHours(1)));
        when(exchange.createElicitation(any())).thenReturn(new McpSchema.ElicitResult(
            McpSchema.ElicitResult.Action.ACCEPT,
            Map.of("disposition", "USER_PROVIDED", "answer", "answer", "sourceName", "forbidden")));
        assertCode("REQUIREMENT_SOURCE_FORBIDDEN", () -> execute("scenario_workflow_answer", Map.of(
            "workflowId", answer.id(), "expectedRevision", 0, "topic", "GOAL_AND_RISK")));

        when(exchange.createElicitation(any())).thenReturn(new McpSchema.ElicitResult(
            McpSchema.ElicitResult.Action.ACCEPT,
            Map.of("disposition", "NOT_APPLICABLE", "answer", "not required", "sourceDigest", SHA)));
        assertCode("REQUIREMENT_SOURCE_FORBIDDEN", () -> execute("scenario_workflow_answer", Map.of(
            "workflowId", answer.id(), "expectedRevision", 0, "topic", "GOAL_AND_RISK")));

        when(exchange.createElicitation(any())).thenReturn(accepted("INFERRED", "not allowed"));
        assertCode("REQUIREMENT_DISPOSITION_INVALID", () -> execute("scenario_workflow_answer", Map.of(
            "workflowId", answer.id(), "expectedRevision", 0, "topic", "GOAL_AND_RISK")));
    }

    @Test
    void preservesGeneratedTextExactlyIncludingWhitespaceAndEmptyFiles() {
        ScenarioWorkflow workflow = fullyAnsweredWorkflow("wf-exact-files", "as-exact-files");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-exact-files",
            AgentSession.open("as-exact-files", PRINCIPAL, NOW, Duration.ofHours(1)));
        when(owners.get("/scenario-manager/api/authoring-contract/fingerprint"))
            .thenReturn(Map.of("fingerprint", "cap-a"));

        @SuppressWarnings("unchecked")
        Map<String, Object> generated = (Map<String, Object>) execute("scenario_workflow_generate", Map.of(
            "workflowId", workflow.id(),
            "expectedRevision", workflow.revision(),
            "files", List.of(
                Map.of("path", "empty.txt", "content", ""),
                Map.of("path", "templates/request.txt", "content", "  leading\ntrailing  \n"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) generated.get("files");
        assertThat(files).containsExactly(
            Map.of("path", "empty.txt", "content", "", "sha256",
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            Map.of("path", "templates/request.txt", "content", "  leading\ntrailing  \n", "sha256",
                "sha256:dad8d8c12ed46c6ddc48b31f103d94d1a12af1df89492ffc9c94e27214771ec3"));
    }

    @Test
    void rejectsDuplicateGeneratedFilePaths() {
        ScenarioWorkflow workflow = fullyAnsweredWorkflow("wf-duplicate-files", "as-duplicate-files");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-duplicate-files",
            AgentSession.open("as-duplicate-files", PRINCIPAL, NOW, Duration.ofHours(1)));

        assertCode("BUNDLE_FILE_PATH_DUPLICATE", () -> execute("scenario_workflow_generate", Map.of(
            "workflowId", workflow.id(),
            "expectedRevision", workflow.revision(),
            "files", List.of(
                Map.of("path", "scenario.yaml", "content", "first"),
                Map.of("path", "scenario.yaml", "content", "second")))));
    }

    @Test
    void hidesForeignAndMissingStateAndPersistsExpiry() {
        PrincipalKey foreign = new PrincipalKey(PRINCIPAL.issuer(), "other");
        state.sessions.put("foreign", AgentSession.open("foreign", foreign, NOW, Duration.ofHours(1)));
        state.workflows.put("foreign-wf", ScenarioWorkflow.create("foreign-wf", "foreign", foreign));
        assertCode("AGENT_SESSION_NOT_FOUND",
            () -> execute("agent_session_get", Map.of("agentSessionId", "foreign")));
        assertCode("AGENT_SESSION_NOT_FOUND",
            () -> execute("agent_session_get", Map.of("agentSessionId", "missing")));
        assertCode("SCENARIO_WORKFLOW_NOT_FOUND",
            () -> execute("scenario_workflow_get", Map.of("workflowId", "foreign-wf")));
        assertCode("SCENARIO_WORKFLOW_NOT_FOUND",
            () -> execute("scenario_workflow_get", Map.of("workflowId", "missing")));

        AgentSession expired = AgentSession.open("expired", PRINCIPAL, NOW.minus(Duration.ofHours(2)), Duration.ofHours(1));
        state.sessions.put(expired.id(), expired);
        execute("agent_session_get", Map.of("agentSessionId", expired.id()));
        assertThat(expired.state().name()).isEqualTo("EXPIRED");
        assertThat(state.savedSessions).contains(expired.id());
        assertThat(state.maintenanceCalls).isPositive();
        ScenarioWorkflow workflow = ScenarioWorkflow.create("wf-expired", expired.id(), PRINCIPAL);
        state.workflows.put(workflow.id(), workflow);
        assertCode("AGENT_SESSION_NOT_OPEN", () -> execute("scenario_workflow_cancel", Map.of(
            "workflowId", workflow.id(), "expectedRevision", 0)));
    }

    @Test
    void rejectsValidationWhenRevisionOrStateDoesNotMatch() {
        ScenarioWorkflow workflow = workflow("wf-validation");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-answer", AgentSession.open("as-answer", PRINCIPAL, NOW, Duration.ofHours(1)));
        Map<String, Object> input = bundleInput();
        input.put("workflowId", workflow.id());
        input.put("expectedRevision", 99);
        assertCode("WORKFLOW_VERSION_CONFLICT", () -> execute("scenario_bundle_validation_prepare", input));
        input.put("expectedRevision", 0);
        assertCode("WORKFLOW_NOT_GENERATED", () -> execute("scenario_bundle_validation_prepare", input));
        verify(uploads, never()).prepareValidation(any(), any(), any(), any(), any());
    }

    @Test
    void normalizesPublicationIntentFailuresToOneTypedError() {
        when(uploads.validationReceipt("receipt-a", PRINCIPAL)).thenReturn(receipt(UploadWorkflowBinding.direct()));
        Map<String, Object> input = bundleInput();
        input.put("validationReceiptId", "receipt-a");
        input.put("mode", "UPSERT");
        input.put("archiveDigest", SHA);
        input.put("bundleContentDigest", SHA);

        assertCode("PUBLICATION_INTENT_INVALID", () -> execute("scenario_bundle_publication_prepare", input));
    }

    @Test
    void usesExplicitUnknownClientIdentityWhenTheClientOmitsImplementationMetadata() {
        ScenarioWorkflow workflow = workflow("wf-unknown-client");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-answer", AgentSession.open("as-answer", PRINCIPAL, NOW, Duration.ofHours(1)));
        when(exchange.getClientInfo()).thenReturn(null);
        when(exchange.createElicitation(any())).thenReturn(accepted("USER_PROVIDED", "explicit"));

        execute("scenario_workflow_answer", Map.of(
            "workflowId", workflow.id(), "expectedRevision", 0, "topic", "GOAL_AND_RISK"));

        var provenance = workflow.requirements().get(QaRequirementTopic.GOAL_AND_RISK).provenance();
        assertThat(provenance.declaredClientName()).isEqualTo("unknown");
        assertThat(provenance.declaredClientVersion()).isEqualTo("unknown");
    }

    @Test
    void serializationAndRequiredDigestProviderFailuresAreExplicit() throws Exception {
        ScenarioWorkflow workflow = workflow("wf-failure");
        state.workflows.put(workflow.id(), workflow);
        state.sessions.put("as-answer", AgentSession.open("as-answer", PRINCIPAL, NOW, Duration.ofHours(1)));
        when(exchange.createElicitation(any())).thenReturn(accepted("USER_PROVIDED", "explicit"));

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("cannot serialize") { });
        McpToolExecutor serialization = new McpToolExecutor(
            owners, properties(), failingMapper, uploads, state, readiness, Clock.fixed(NOW, ZoneOffset.UTC));
        assertCode("TOOL_RESULT_SERIALIZATION_FAILED", () -> serialization.execute(
            catalogue.requireTool("scenario_workflow_answer"), exchange,
            Map.of("workflowId", workflow.id(), "expectedRevision", 0, "topic", "GOAL_AND_RISK")));

        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256"))
                .thenThrow(new NoSuchAlgorithmException("missing"));
            assertThatThrownBy(() -> execute("scenario_workflow_answer", Map.of(
                "workflowId", workflow.id(), "expectedRevision", 0, "topic", "GOAL_AND_RISK")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SHA-256 is required by Java");
        }
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
        verify(owners).post("/orchestrator/api/swarms/swarm%2Fa/" + action, Map.of("idempotencyKey", "idem-a"));
        clearInvocations(owners);
    }

    private void verifyAggregateCalls(Map<String, Object> input, boolean includesResources) {
        verify(owners).get("/orchestrator/api/swarms/swarm%2Fa");
        if (includesResources) {
            verify(owners).post("/orchestrator/api/runtime/debug/resources/list", input);
        } else {
            verify(owners, never()).post("/orchestrator/api/runtime/debug/resources/list", input);
        }
        verify(owners).post("/orchestrator/api/runtime/debug/rabbit/topology", input);
        clearInvocations(owners);
    }

    private static McpSchema.ElicitResult accepted(String disposition, String answer) {
        return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
            Map.of("disposition", disposition, "answer", answer));
    }

    private static Map<String, Object> ownerInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scenario/a");
        input.put("bundleKey", "bundle/a");
        input.put("path", "schema a.json");
        input.put("swarmId", "swarm/a");
        input.put("templateId", "template-a");
        input.put("sutId", "sut-a");
        input.put("variablesProfileId", "vars-a");
        input.put("idempotencyKey", "idem-a");
        input.put("limit", 25);
        input.put("runId", "run/a");
        input.put("severity", "WARN");
        input.put("tapId", "tap/a");
        input.put("drain", true);
        input.put("role", "generator");
        input.put("instanceId", "gen/a");
        input.put("patch", Map.of("rate", 5));
        input.put("runtimeId", "runtime-a");
        input.put("candidateSetHash", SHA);
        input.put("candidateIds", List.of("runtime-a"));
        input.put("reason", "reviewed cleanup");
        return input;
    }

    private static Map<String, Object> bundleInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("source", Map.of(
            "repository", "git@example/repo", "commit", COMMIT, "bundlePath", "scenarios/sample",
            "verification", "CLIENT_ASSERTED"));
        input.put("fileManifest", manifestInput());
        return input;
    }

    private static List<Map<String, Object>> manifestInput() {
        return List.of(Map.of("path", "scenario.yaml", "byteCount", 4, "sha256", SHA));
    }

    private static String json(Object value) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private static BundleValidationReceipt receipt(UploadWorkflowBinding binding) {
        return new BundleValidationReceipt("receipt-a", PRINCIPAL, binding,
            new SourceMetadata("git@example/repo", COMMIT, "scenarios/sample", SourceVerification.CLIENT_ASSERTED),
            new io.pockethive.mcp.domain.BundleFileManifest(List.of(
                new io.pockethive.mcp.domain.BundleFileManifestEntry("scenario.yaml", 4, SHA))),
            SHA, SHA, "scenario-a", "Scenario A", NOW);
    }

    private static ScenarioWorkflow workflow(String id) {
        return ScenarioWorkflow.create(id, "as-answer", PRINCIPAL);
    }

    private static ScenarioWorkflow fullyAnsweredWorkflow(String id, String sessionId) {
        ScenarioWorkflow workflow = ScenarioWorkflow.create(id, sessionId, PRINCIPAL);
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            workflow.answer(workflow.revision(), topic,
                io.pockethive.mcp.domain.RequirementAnswer.notApplicable("not required",
                    new io.pockethive.mcp.domain.AnswerProvenance(
                        PRINCIPAL, "test-client", "test-client", "1.0.0", id, workflow.revision(),
                        topic.name(), SHA, io.pockethive.mcp.domain.ElicitationAction.ACCEPT, SHA, NOW)));
        }
        return workflow;
    }

    private static ScenarioWorkflow generatedWorkflow(String id, String sessionId) {
        ScenarioWorkflow workflow = fullyAnsweredWorkflow(id, sessionId);
        workflow.readyToGenerate(workflow.revision(),
            new io.pockethive.mcp.domain.CapabilityFingerprint(SHA, NOW));
        workflow.generated(workflow.revision(), SHA);
        return workflow;
    }

    @SuppressWarnings("unchecked")
    private static String textField(Object value, String field) {
        Object result = ((Map<String, Object>) value).get(field);
        return result instanceof Enum<?> enumeration ? enumeration.name() : String.valueOf(result);
    }

    private static void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ToolExecutionException.class)
            .extracting(exception -> ((ToolExecutionException) exception).code())
            .isEqualTo(code);
    }

    private static PocketHiveMcpProperties properties() {
        URI ingress = URI.create("http://127.0.0.1:8080");
        return new PocketHiveMcpProperties(
            ingress, ingress, "2025-11-25", PocketHiveMcpProperties.StateMode.MEMORY,
            Path.of("target/state"), Path.of("target/spool"), Duration.ofMinutes(30), Duration.ofHours(1),
            Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(5), 100, 10, 100, 10, 10_000_000,
            2, 10, 10_000_000, 20_000_000, 200, 20_000_000, 8, 100,
            List.of("http://127.0.0.1:8080"), List.of("127.0.0.1:8080"), ingress,
            URI.create("http://127.0.0.1:8080/mcp"), URI.create("http://127.0.0.1:8080/oauth/introspect"),
            "mcp", "secret", "pockethive-mcp", "service-secret");
    }

    private static final class MemoryState implements CoordinationStateRepository {
        private final Map<String, AgentSession> sessions = new HashMap<>();
        private final Map<String, ScenarioWorkflow> workflows = new HashMap<>();
        private final Map<String, List<Map<String, Object>>> generatedFiles = new HashMap<>();
        private final List<String> savedSessions = new java.util.ArrayList<>();
        private final List<String> savedWorkflowsWithFiles = new java.util.ArrayList<>();
        private final List<String> removedGeneratedFiles = new java.util.ArrayList<>();
        private final List<String> findWorkflowCalls = new java.util.ArrayList<>();
        private Instant maintainedAt;
        private int maintenanceCalls;

        @Override
        public Optional<AgentSession> findSession(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public Optional<ScenarioWorkflow> findWorkflow(String workflowId) {
            findWorkflowCalls.add(workflowId);
            return Optional.ofNullable(workflows.get(workflowId));
        }

        @Override
        public List<ScenarioWorkflow> findWorkflows(List<String> workflowIds) {
            return workflowIds.stream().map(workflows::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public List<Map<String, Object>> findGeneratedFiles(String workflowId) {
            return generatedFiles.getOrDefault(workflowId, List.of());
        }

        @Override
        public void createSession(AgentSession session) {
            sessions.put(session.id(), session);
        }

        @Override
        public void saveSession(AgentSession session) {
            sessions.put(session.id(), session);
            savedSessions.add(session.id());
        }

        @Override
        public void createWorkflow(AgentSession session, ScenarioWorkflow workflow) {
            sessions.put(session.id(), session);
            workflows.put(workflow.id(), workflow);
        }

        @Override
        public void saveWorkflow(ScenarioWorkflow workflow, List<Map<String, Object>> files) {
            workflows.put(workflow.id(), workflow);
            generatedFiles.put(workflow.id(), List.copyOf(files));
            savedWorkflowsWithFiles.add(workflow.id());
        }

        @Override
        public void saveWorkflow(ScenarioWorkflow workflow) {
            workflows.put(workflow.id(), workflow);
        }

        @Override
        public void saveWorkflowAndRemoveGeneratedFiles(ScenarioWorkflow workflow) {
            workflows.put(workflow.id(), workflow);
            generatedFiles.remove(workflow.id());
            removedGeneratedFiles.add(workflow.id());
        }

        @Override
        public long countOpenSessions(PrincipalKey principal) {
            return sessions.values().stream().filter(session -> session.principal().equals(principal)).count();
        }

        @Override
        public UploadCoordinationSnapshot loadUploadCoordination() {
            return UploadCoordinationSnapshot.empty();
        }

        @Override
        public void saveUploadCoordination(UploadCoordinationSnapshot uploadCoordination) {
        }

        @Override
        public void maintainSessions(Instant now, Duration terminalRetention) {
            maintainedAt = now;
            maintenanceCalls++;
        }
    }
}
