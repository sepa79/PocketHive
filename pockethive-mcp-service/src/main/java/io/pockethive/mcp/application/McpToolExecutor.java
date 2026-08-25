package io.pockethive.mcp.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.pockethive.mcp.adapter.mcp.McpCaller;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.AnswerProvenance;
import io.pockethive.mcp.domain.CapabilityFingerprint;
import io.pockethive.mcp.domain.ConfirmedSource;
import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import io.pockethive.mcp.domain.ElicitationAction;
import io.pockethive.mcp.domain.QaRequirementTopic;
import io.pockethive.mcp.domain.RequirementAnswer;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

@Service
public class McpToolExecutor {
    private static final String SCENARIO_PREFIX = "/scenario-manager";
    private static final String ORCHESTRATOR_PREFIX = "/orchestrator";

    private final OwnerApiPort owners;
    private final PocketHiveMcpProperties properties;
    private final ObjectMapper mapper;
    private final BundleUploadCoordinator uploads;
    private final CoordinationStateRepository state;
    private final SwarmReadinessObserver readiness;
    private final Clock clock;

    public McpToolExecutor(OwnerApiPort owners, PocketHiveMcpProperties properties, ObjectMapper mapper,
                           BundleUploadCoordinator uploads, CoordinationStateRepository state,
                           SwarmReadinessObserver readiness, Clock clock) {
        this.owners = owners;
        this.properties = properties;
        this.mapper = mapper;
        this.uploads = uploads;
        this.state = state;
        this.readiness = readiness;
        this.clock = clock;
    }

    public synchronized Object execute(ToolDescriptor descriptor, McpSyncServerExchange exchange,
                                       Map<String, Object> arguments) {
        McpCaller caller = McpCaller.from(exchange.transportContext());
        requireScope(caller, descriptor.requiredScope());
        return switch (descriptor.owner()) {
            case MCP -> executeMcp(descriptor.id(), exchange, caller, arguments);
            case SCENARIO_MANAGER, ORCHESTRATOR -> executeOwner(descriptor.id(), arguments);
        };
    }

    private Object executeMcp(String toolId, McpSyncServerExchange exchange, McpCaller caller,
                              Map<String, Object> input) {
        return switch (toolId) {
            case "agent_session_create" -> createSession(caller);
            case "agent_session_get" -> sessionView(requireSession(text(input, "agentSessionId"), caller));
            case "agent_session_list_workflows", "scenario_workflow_list" -> listWorkflows(
                requireSession(text(input, "agentSessionId"), caller));
            case "agent_session_close" -> closeSession(input, caller);
            case "scenario_workflow_create" -> createWorkflow(input, caller);
            case "scenario_workflow_get" -> workflowView(requireWorkflow(text(input, "workflowId"), caller));
            case "scenario_workflow_answer" -> answerWorkflow(exchange, input, caller);
            case "scenario_workflow_question" -> questionWorkflow(input, caller);
            case "scenario_workflow_answer_submit" -> submitWorkflowAnswer(exchange, input, caller);
            case "scenario_workflow_review_prepare" -> prepareWorkflowReview(input, caller);
            case "scenario_workflow_review_submit" -> submitWorkflowReview(exchange, input, caller);
            case "scenario_workflow_generate" -> generateWorkflow(input, caller);
            case "scenario_workflow_cancel" -> cancelWorkflow(input, caller);
            case "scenario_bundle_validation_prepare" -> prepareValidation(input, caller);
            case "scenario_bundle_direct_validation_prepare" -> validationTicketView(
                uploads.prepareDirectValidationWithCapability(caller.principal(), source(input), manifest(input),
                    clock.instant()));
            case "scenario_bundle_validation_receipt_get" -> BundleValidationReceiptView.from(
                uploads.validationReceipt(text(input, "receiptId"), caller.principal()));
            case "scenario_bundle_publication_prepare" -> preparePublication(input, caller);
            case "scenario_bundle_publication_attempt_get" -> PublicationAttemptView.from(
                uploads.publicationAttempt(text(input, "attemptId"), caller.principal()));
            case "scenario_bundle_publication_reconcile" -> PublicationAttemptView.from(
                uploads.reconcile(text(input, "attemptId"), caller.principal()));
            default -> throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId);
        };
    }

    private Object executeOwner(String toolId, Map<String, Object> input) {
        return switch (toolId) {
            case "scenario_list" -> owners.get(SCENARIO_PREFIX + "/scenarios");
            case "scenario_get" -> owners.get(SCENARIO_PREFIX + "/scenarios/" + segment(input, "scenarioId"));
            case "scenario_raw_read" -> owners.getText(SCENARIO_PREFIX + "/scenarios/" + segment(input, "scenarioId") + "/raw");
            case "scenario_schema_read" -> owners.getText(SCENARIO_PREFIX + "/scenarios/" + segment(input, "scenarioId")
                + "/schema?path=" + query(input, "path"));
            case "scenario_template_read" -> owners.getText(SCENARIO_PREFIX + "/scenarios/" + segment(input, "scenarioId")
                + "/template?path=" + query(input, "path"));
            case "scenario_bundle_tree_read" -> owners.get(SCENARIO_PREFIX + "/scenarios/bundles/tree?bundleKey="
                + query(input, "bundleKey"));
            case "scenario_bundle_file_read" -> owners.get(SCENARIO_PREFIX + "/scenarios/bundles/file?bundleKey="
                + query(input, "bundleKey") + "&path=" + query(input, "path"));
            case "scenario_suts_list" -> owners.get(SCENARIO_PREFIX + "/scenarios/" + segment(input, "scenarioId") + "/suts");
            case "scenario_sut_get" -> owners.get(SCENARIO_PREFIX + "/scenarios/" + segment(input, "scenarioId")
                + "/suts/" + segment(input, "sutId"));
            case "scenario_contracts_get" -> scenarioContracts();
            case "scenario_capabilities_get" -> owners.get(scenarioCapabilitiesPath(input));
            case "scenario_templates_catalog" -> owners.get(SCENARIO_PREFIX + "/api/templates");
            case "swarm_list" -> owners.get(ORCHESTRATOR_PREFIX + "/api/swarms");
            case "swarm_get" -> owners.get(ORCHESTRATOR_PREFIX + "/api/swarms/" + segment(input, "swarmId"));
            case "swarm_wait_ready" -> readiness.observe(
                ORCHESTRATOR_PREFIX + "/api/swarms/" + segment(input, "swarmId"),
                text(input, "swarmId"));
            case "swarm_create" -> owners.post(ORCHESTRATOR_PREFIX + "/api/swarms/" + segment(input, "swarmId") + "/create",
                swarmCreateBody(input));
            case "swarm_start" -> lifecycle(input, "start");
            case "swarm_stop" -> lifecycle(input, "stop");
            case "swarm_remove" -> lifecycle(input, "remove");
            case "debug_journal" -> owners.get(ORCHESTRATOR_PREFIX + "/api/swarms/" + segment(input, "swarmId")
                + "/journal/page?limit=" + queryOr(input, "limit", "50")
                + optionalSegmentQuery(input, "runId") + optionalQuery(input, "severity"));
            case "debug_journal_runs" -> owners.get(ORCHESTRATOR_PREFIX + "/api/swarms/"
                + segment(input, "swarmId") + "/journal/runs");
            case "debug_hive_journal" -> owners.get(ORCHESTRATOR_PREFIX + "/api/journal/hive/page?limit="
                + queryOr(input, "limit", "50"));
            case "debug_tap" -> owners.post(ORCHESTRATOR_PREFIX + "/api/debug/taps", input);
            case "debug_tap_read" -> owners.get(ORCHESTRATOR_PREFIX + "/api/debug/taps/" + segment(input, "tapId")
                + (input.containsKey("drain") ? "?drain=" + query(input, "drain") : ""));
            case "debug_tap_close" -> owners.delete(ORCHESTRATOR_PREFIX + "/api/debug/taps/" + segment(input, "tapId"));
            case "component_config_preview" -> owners.post(ORCHESTRATOR_PREFIX + "/api/components/"
                + segment(input, "role") + "/" + segment(input, "instanceId") + "/config/preview",
                body(input, "swarmId", "patch"));
            case "component_config_update" -> owners.post(ORCHESTRATOR_PREFIX + "/api/components/"
                + segment(input, "role") + "/" + segment(input, "instanceId") + "/config",
                body(input, "swarmId", "patch", "idempotencyKey"));
            case "runtime_cleanup_plan" -> owners.post(ORCHESTRATOR_PREFIX + "/api/runtime/cleanup/plan",
                cleanupPlanBody(input));
            case "runtime_cleanup_execute" -> owners.post(ORCHESTRATOR_PREFIX + "/api/runtime/cleanup/execute",
                cleanupExecuteBody(input));
            case "runtime_tail_worker_logs" -> owners.post(ORCHESTRATOR_PREFIX + "/api/runtime/debug/resources/logs", input);
            case "runtime_get_worker_version" -> owners.post(ORCHESTRATOR_PREFIX + "/api/runtime/debug/resources/version", input);
            case "runtime_list_workers" -> owners.post(ORCHESTRATOR_PREFIX + "/api/runtime/debug/resources/list", input);
            case "runtime_inspect_worker" -> owners.post(ORCHESTRATOR_PREFIX + "/api/runtime/debug/resources/inspect", input);
            case "runtime_rabbit_topology_snapshot" -> owners.post(ORCHESTRATOR_PREFIX + "/api/runtime/debug/rabbit/topology", input);
            case "runtime_assess_swarm", "runtime_diff_swarm_runtime", "runtime_control_plane_status",
                 "runtime_manifest_validate" -> owners.post(
                    ORCHESTRATOR_PREFIX + "/api/runtime/debug/assessment", runtimeAssessmentBody(input));
            case "runtime_swarm_timeline" -> runtimeTimeline(input);
            default -> throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId);
        };
    }

    private Object scenarioContracts() {
        return Map.of(
            "authoringContract", owners.get(SCENARIO_PREFIX + "/api/authoring-contract"),
            "fingerprint", owners.get(SCENARIO_PREFIX + "/api/authoring-contract/fingerprint"));
    }

    private static String scenarioCapabilitiesPath(Map<String, Object> input) {
        Set<String> selectors = Set.of("all", "imageName", "imageDigest");
        if (input.keySet().stream().anyMatch(key -> !selectors.contains(key))) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", "scenario capability selector");
        }
        long selected = selectors.stream().filter(input::containsKey).count();
        if (selected > 1) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", "select exactly one scenario capability selector");
        }
        if (input.containsKey("all")) {
            if (!booleanValue(input, "all")) {
                throw new ToolExecutionException("TOOL_INPUT_INVALID", "all must be true");
            }
            return SCENARIO_PREFIX + "/api/capabilities?all=true";
        }
        if (input.containsKey("imageName")) {
            return SCENARIO_PREFIX + "/api/capabilities?imageName=" + query(input, "imageName");
        }
        if (input.containsKey("imageDigest")) {
            return SCENARIO_PREFIX + "/api/capabilities?imageDigest=" + query(input, "imageDigest");
        }
        return SCENARIO_PREFIX + "/api/capabilities?all=true";
    }

    private Object lifecycle(Map<String, Object> input, String action) {
        return owners.post(ORCHESTRATOR_PREFIX + "/api/swarms/" + segment(input, "swarmId") + "/" + action,
            Map.of("idempotencyKey", text(input, "idempotencyKey")));
    }

    private static Map<String, Object> runtimeAssessmentBody(Map<String, Object> input) {
        return body(input, "swarmId", "runId");
    }

    private static Map<String, Object> cleanupPlanBody(Map<String, Object> input) {
        booleanValue(input, "includeRunning");
        booleanValue(input, "includeRabbit");
        return body(input, "swarmId", "runId", "includeRunning", "includeRabbit");
    }

    private static Map<String, Object> cleanupExecuteBody(Map<String, Object> input) {
        booleanValue(input, "includeRunning");
        booleanValue(input, "includeRabbit");
        return body(input, "swarmId", "runId", "includeRunning", "includeRabbit",
            "candidateSetHash", "candidateIds", "idempotencyKey", "reason", "actor");
    }

    private Object runtimeTimeline(Map<String, Object> input) {
        return owners.get(ORCHESTRATOR_PREFIX + "/api/swarms/" + segment(input, "swarmId")
            + "/journal/page?limit=" + queryOr(input, "limit", "100"));
    }

    private Object createSession(McpCaller caller) {
        Instant now = clock.instant();
        state.maintainSessions(now, properties.closedSessionRetention());
        String id = "as-" + UUID.randomUUID();
        AgentSession session = AgentSession.open(id, caller.principal(), now, properties.openSessionTtl());
        state.createSession(session);
        return sessionView(session);
    }

    private Object closeSession(Map<String, Object> input, McpCaller caller) {
        AgentSession session = requireSession(text(input, "agentSessionId"), caller);
        session.close(number(input, "expectedRevision"), clock.instant());
        state.saveSession(session);
        return sessionView(session);
    }

    private Object createWorkflow(Map<String, Object> input, McpCaller caller) {
        AgentSession session = requireSession(text(input, "agentSessionId"), caller);
        String id = "wf-" + UUID.randomUUID();
        session.addWorkflow(number(input, "expectedSessionRevision"), id, properties.maxWorkflowsPerSession());
        ScenarioWorkflow workflow = ScenarioWorkflow.create(id, session.id(), caller.principal());
        state.createWorkflow(session, workflow);
        return workflowView(workflow);
    }

    private Object answerWorkflow(McpSyncServerExchange exchange, Map<String, Object> input, McpCaller caller) {
        requireFormElicitation(exchange);
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        long expectedRevision = number(input, "expectedRevision");
        QaRequirementTopic topic = topic(input);
        QaRequirementQuestionContract question = QaRequirementQuestionContract.forTopic(
            topic, QaAnswerCaptureMode.MCP_FORM);
        McpSchema.ElicitResult result = exchange.createElicitation(
            McpSchema.ElicitFormRequest.builder(question.message(), question.responseSchema()).build());
        if (result == null) {
            throw new ToolExecutionException("ELICITATION_RESULT_INVALID", "The client returned no elicitation result");
        }
        if (result.action() != McpSchema.ElicitResult.Action.ACCEPT) {
            return Map.of(
                "workflowId", workflow.id(),
                "state", workflow.state(),
                "revision", workflow.revision(),
                "topic", topic,
                "elicitationAction", result.action(),
                "disposition", "UNKNOWN");
        }
        return applyAcceptedAnswer(exchange, caller, workflow, expectedRevision, question, result.content());
    }

    private Object questionWorkflow(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        QaRequirementQuestionContract question = QaRequirementQuestionContract.forTopic(
            topic(input), QaAnswerCaptureMode.AGENT_MEDIATED);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflow.id());
        result.put("workflowRevision", workflow.revision());
        result.put("topic", question.topic().name());
        result.put("captureMode", question.captureMode().name());
        result.put("questionId", question.questionId());
        result.put("message", question.message());
        result.put("responseSchema", question.responseSchema());
        result.put("requestedSchemaDigest", schemaDigest(question));
        return result;
    }

    private Object submitWorkflowAnswer(McpSyncServerExchange exchange, Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        long expectedRevision = number(input, "expectedRevision");
        if (workflow.revision() != expectedRevision) {
            throw new ToolExecutionException("WORKFLOW_VERSION_CONFLICT", workflow.id());
        }
        QaRequirementQuestionContract question = QaRequirementQuestionContract.forTopic(
            topic(input), QaAnswerCaptureMode.AGENT_MEDIATED);
        if (!question.questionId().equals(text(input, "questionId"))) {
            throw new ToolExecutionException("QA_QUESTION_ID_MISMATCH", question.questionId());
        }
        String requestedSchemaDigest = text(input, "requestedSchemaDigest");
        if (!schemaDigest(question).equals(requestedSchemaDigest)) {
            throw new ToolExecutionException("QA_QUESTION_SCHEMA_MISMATCH", question.questionId());
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("disposition", text(input, "disposition"));
        content.put("answer", text(input, "answer"));
        if (input.containsKey("sourceName")) {
            content.put("sourceName", text(input, "sourceName"));
        }
        if (input.containsKey("sourceDigest")) {
            content.put("sourceDigest", text(input, "sourceDigest"));
        }
        return applyAcceptedAnswer(exchange, caller, workflow, expectedRevision, question, content);
    }

    private Object prepareWorkflowReview(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        requireWorkflowRevision(workflow, number(input, "expectedRevision"));
        QaRequirementReviewContract review = reviewContract(input);
        return workflowReviewView(workflow, review);
    }

    private Object submitWorkflowReview(McpSyncServerExchange exchange, Map<String, Object> input,
                                        McpCaller caller) {
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        long expectedRevision = number(input, "expectedRevision");
        requireWorkflowRevision(workflow, expectedRevision);
        if (!QaRequirementReviewContract.REVIEW_ID.equals(text(input, "reviewId"))) {
            throw new ToolExecutionException("QA_REVIEW_ID_MISMATCH", QaRequirementReviewContract.REVIEW_ID);
        }
        String requestedSchemaDigest = text(input, "requestedSchemaDigest");
        String canonicalSchemaDigest = reviewSchemaDigest();
        if (!canonicalSchemaDigest.equals(requestedSchemaDigest)) {
            throw new ToolExecutionException("QA_REVIEW_SCHEMA_MISMATCH", QaRequirementReviewContract.REVIEW_ID);
        }
        QaRequirementReviewContract review = reviewContract(input);
        String answerSetDigest = reviewDigest(review);
        if (!answerSetDigest.equals(text(input, "answerSetDigest"))) {
            throw new ToolExecutionException("QA_REVIEW_ANSWER_SET_MISMATCH", QaRequirementReviewContract.REVIEW_ID);
        }
        String clientName = exchange.getClientInfo() == null ? "unknown" : exchange.getClientInfo().name();
        String clientVersion = exchange.getClientInfo() == null ? "unknown" : exchange.getClientInfo().version();
        Map<QaRequirementTopic, RequirementAnswer> requirements = new EnumMap<>(QaRequirementTopic.class);
        for (QaRequirementReviewContract.ReviewAnswer answer : review.answers()) {
            AnswerProvenance provenance = new AnswerProvenance(
                caller.principal(), caller.clientId(), clientName, clientVersion,
                workflow.id(), expectedRevision, QaAnswerCaptureMode.COMPACT_REVIEW.questionId(answer.topic()),
                requestedSchemaDigest, ElicitationAction.ACCEPT, answerSetDigest, clock.instant());
            RequirementAnswer requirement = reviewRequirement(answer, review.source(), provenance);
            requirements.put(answer.topic(), requirement);
        }
        workflow.answerAll(expectedRevision, requirements);
        state.saveWorkflowAndRemoveGeneratedFiles(workflow);
        return workflowView(workflow);
    }

    private static RequirementAnswer reviewRequirement(QaRequirementReviewContract.ReviewAnswer answer,
                                                       ConfirmedSource source, AnswerProvenance provenance) {
        if (answer.disposition() == io.pockethive.mcp.domain.RequirementDisposition.USER_PROVIDED) {
            return RequirementAnswer.userProvided(answer.answer(), provenance);
        }
        if (answer.disposition() == io.pockethive.mcp.domain.RequirementDisposition.USER_CONFIRMED_SOURCE) {
            return RequirementAnswer.userConfirmedSource(answer.answer(), source, provenance);
        }
        return RequirementAnswer.notApplicable(answer.answer(), provenance);
    }

    private Map<String, Object> workflowReviewView(ScenarioWorkflow workflow,
                                                   QaRequirementReviewContract review) {
        String canonicalJson = json(review.canonicalPayload());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflow.id());
        result.put("workflowRevision", workflow.revision());
        result.put("captureMode", QaAnswerCaptureMode.COMPACT_REVIEW.name());
        result.put("reviewId", QaRequirementReviewContract.REVIEW_ID);
        result.put("message", review.reviewMessage(canonicalJson));
        result.put("responseSchema", QaRequirementReviewContract.responseSchema());
        result.put("requestedSchemaDigest", reviewSchemaDigest());
        result.put("answerSetDigest", sha256(canonicalJson));
        result.putAll(review.canonicalPayload());
        return result;
    }

    private QaRequirementReviewContract reviewContract(Map<String, Object> input) {
        return QaRequirementReviewContract.parse(
            require(input, "answers"), optionalText(input, "sourceName"), optionalText(input, "sourceDigest"));
    }

    private String reviewDigest(QaRequirementReviewContract review) {
        return sha256(json(review.canonicalPayload()));
    }

    private String reviewSchemaDigest() {
        return sha256(json(QaRequirementReviewContract.responseSchema()));
    }

    private Object applyAcceptedAnswer(McpSyncServerExchange exchange, McpCaller caller,
                                       ScenarioWorkflow workflow, long expectedRevision,
                                       QaRequirementQuestionContract question, Map<String, Object> content) {
        question.validateResponseFields(content);
        String disposition = text(content, "disposition");
        String answer = text(content, "answer");
        String contentDigest = sha256(json(content));
        String clientName = exchange.getClientInfo() == null ? "unknown" : exchange.getClientInfo().name();
        String clientVersion = exchange.getClientInfo() == null ? "unknown" : exchange.getClientInfo().version();
        AnswerProvenance provenance = new AnswerProvenance(
            caller.principal(), caller.clientId(), clientName, clientVersion,
            workflow.id(), expectedRevision, question.questionId(), schemaDigest(question),
            ElicitationAction.ACCEPT, contentDigest, clock.instant());
        RequirementAnswer requirement = switch (disposition) {
            case "USER_PROVIDED" -> {
                rejectSourceFields(content);
                yield RequirementAnswer.userProvided(answer, provenance);
            }
            case "USER_CONFIRMED_SOURCE" -> RequirementAnswer.userConfirmedSource(answer,
                new ConfirmedSource(text(content, "sourceName"),
                    text(content, "sourceDigest")), provenance);
            case "NOT_APPLICABLE" -> {
                rejectSourceFields(content);
                yield RequirementAnswer.notApplicable(answer, provenance);
            }
            default -> throw new ToolExecutionException("REQUIREMENT_DISPOSITION_INVALID", disposition);
        };
        workflow.answer(expectedRevision, question.topic(), requirement);
        state.saveWorkflowAndRemoveGeneratedFiles(workflow);
        return workflowView(workflow);
    }

    private Object generateWorkflow(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        long expectedRevision = number(input, "expectedRevision");
        List<Map<String, Object>> files = fileList(require(input, "files"));
        String fileDigest = sha256(json(files));
        Object capability = owners.get(SCENARIO_PREFIX + "/api/authoring-contract/fingerprint");
        workflow.readyToGenerate(expectedRevision, new CapabilityFingerprint(sha256(json(capability)), clock.instant()));
        workflow.generated(workflow.revision(), fileDigest);
        state.saveWorkflow(workflow, files);
        Map<String, Object> view = new LinkedHashMap<>(workflowView(workflow));
        view.put("files", files);
        view.put("fileSetDigest", fileDigest);
        return view;
    }

    private Object cancelWorkflow(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        workflow.cancel(number(input, "expectedRevision"));
        state.saveWorkflowAndRemoveGeneratedFiles(workflow);
        return workflowView(workflow);
    }

    private Object prepareValidation(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = requireMutableWorkflow(text(input, "workflowId"), caller);
        long expectedRevision = number(input, "expectedRevision");
        if (workflow.revision() != expectedRevision) {
            throw new ToolExecutionException("WORKFLOW_VERSION_CONFLICT", workflow.id());
        }
        if (workflow.state() != io.pockethive.mcp.domain.ScenarioWorkflowState.GENERATED) {
            throw new ToolExecutionException("WORKFLOW_NOT_GENERATED", workflow.id());
        }
        return validationTicketView(uploads.prepareValidationWithCapability(caller.principal(), workflow.id(),
            source(input), manifest(input), clock.instant()));
    }

    private Object preparePublication(Map<String, Object> input, McpCaller caller) {
        String scenarioId = input.containsKey("scenarioId") ? text(input, "scenarioId") : null;
        try {
            String receiptId = text(input, "validationReceiptId");
            BundleValidationReceipt receipt = uploads.validationReceipt(receiptId, caller.principal());
            if (receipt.workflowBinding().mode() == UploadWorkflowMode.WORKFLOW) {
                requireMutableWorkflow(receipt.workflowBinding().workflowId(), caller);
            }
            return publicationTicketView(uploads.preparePublicationWithCapability(caller.principal(), receiptId,
                PublicationMode.valueOf(text(input, "mode").toUpperCase(Locale.ROOT)), scenarioId,
                source(input), manifest(input), text(input, "archiveDigest"),
                text(input, "bundleContentDigest"), clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("PUBLICATION_INTENT_INVALID", exception.getMessage());
        }
    }

    private ValidationUploadTicketView validationTicketView(
        PreparedUpload<ValidationUploadTicket> prepared) {
        ValidationUploadTicket ticket = prepared.ticket();
        return new ValidationUploadTicketView(ticket.id(), uploadUrl(ticket.id()),
            prepared.uploadCapability(), ticket.expiresAt());
    }

    private PublicationUploadTicketView publicationTicketView(
        PreparedUpload<PublicationUploadTicket> prepared) {
        PublicationUploadTicket ticket = prepared.ticket();
        return new PublicationUploadTicketView(ticket.id(), uploadUrl(ticket.id()), prepared.uploadCapability(),
            ticket.expiresAt(),
            ticket.attemptId(), ticket.validationReceiptId(), ticket.mode(), ticket.scenarioId());
    }

    private URI uploadUrl(String ticketId) {
        return properties.pocketHiveIngress().resolve(BundleUploadContract.PATH_PREFIX + ticketId);
    }

    @SuppressWarnings("unchecked")
    private static SourceMetadata source(Map<String, Object> input) {
        Object value = require(input, "source");
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ToolExecutionException("SOURCE_METADATA_INVALID", "source must be an object");
        }
        Map<String, Object> source = (Map<String, Object>) raw;
        try {
            SourceVerification verification = SourceVerification.valueOf(text(source, "verification"));
            return new SourceMetadata(text(source, "repository"), text(source, "commit"),
                text(source, "bundlePath"), verification);
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("SOURCE_METADATA_INVALID", exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static BundleFileManifest manifest(Map<String, Object> input) {
        Object value = require(input, "fileManifest");
        if (!(value instanceof List<?> list)) {
            throw new ToolExecutionException("BUNDLE_MANIFEST_INVALID", "fileManifest must be an array");
        }
        try {
            return new BundleFileManifest(list.stream().map(item -> {
                if (!(item instanceof Map<?, ?> raw)) {
                    throw new IllegalArgumentException("entry must be an object");
                }
                Map<String, Object> entry = (Map<String, Object>) raw;
                return new BundleFileManifestEntry(text(entry, "path"), number(entry, "byteCount"),
                    text(entry, "sha256"));
            }).toList());
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("BUNDLE_MANIFEST_INVALID", exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fileList(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new ToolExecutionException("BUNDLE_FILES_REQUIRED", "files must be a non-empty array");
        }
        List<Map<String, Object>> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new ToolExecutionException("BUNDLE_FILE_INVALID", "each file must be an object");
            }
            Map<String, Object> file = (Map<String, Object>) raw;
            String path = rawText(file, "path");
            String content = rawText(file, "content");
            if (path.isBlank() || !path.equals(path.strip()) || path.startsWith("/")
                || path.contains("..") || path.contains("\\")) {
                throw new ToolExecutionException("BUNDLE_FILE_PATH_INVALID", path);
            }
            if (!paths.add(path)) {
                throw new ToolExecutionException("BUNDLE_FILE_PATH_DUPLICATE", path);
            }
            Map<String, Object> generatedFile = new LinkedHashMap<>();
            generatedFile.put("path", path);
            generatedFile.put("content", content);
            generatedFile.put("sha256", sha256(content));
            files.add(generatedFile);
        }
        return files.stream().sorted((left, right) -> String.valueOf(left.get("path"))
            .compareTo(String.valueOf(right.get("path")))).toList();
    }

    private AgentSession requireSession(String id, McpCaller caller) {
        Instant now = clock.instant();
        state.maintainSessions(now, properties.closedSessionRetention());
        AgentSession session = state.findSession(id).orElse(null);
        if (session == null) {
            throw new ToolExecutionException("AGENT_SESSION_NOT_FOUND", id);
        }
        if (!session.principal().equals(caller.principal())) {
            throw new ToolExecutionException("AGENT_SESSION_NOT_FOUND", id);
        }
        long revision = session.revision();
        session.expireAt(now);
        if (session.revision() != revision) {
            state.saveSession(session);
        }
        return session;
    }

    private ScenarioWorkflow requireWorkflow(String id, McpCaller caller) {
        ScenarioWorkflow workflow = state.findWorkflow(id).orElse(null);
        if (workflow == null || !workflow.principal().equals(caller.principal())) {
            throw new ToolExecutionException("SCENARIO_WORKFLOW_NOT_FOUND", id);
        }
        return workflow;
    }

    private ScenarioWorkflow requireMutableWorkflow(String id, McpCaller caller) {
        ScenarioWorkflow workflow = requireWorkflow(id, caller);
        AgentSession session = requireSession(workflow.agentSessionId(), caller);
        if (session.state() != io.pockethive.mcp.domain.AgentSessionState.OPEN) {
            throw new ToolExecutionException("AGENT_SESSION_NOT_OPEN", session.id());
        }
        return workflow;
    }

    private Map<String, Object> listWorkflows(AgentSession session) {
        List<Map<String, Object>> items = session.workflowIds().stream()
            .map(state::findWorkflow)
            .flatMap(java.util.Optional::stream)
            .map(this::workflowView)
            .toList();
        return Map.of("agentSessionId", session.id(), "workflows", items, "count", items.size());
    }

    private Map<String, Object> sessionView(AgentSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentSessionId", session.id());
        result.put("state", session.state());
        result.put("revision", session.revision());
        result.put("createdAt", session.createdAt());
        result.put("expiresAt", session.expiresAt());
        result.put("closedAt", session.closedAt());
        result.put("workflowIds", session.workflowIds());
        return result;
    }

    private Map<String, Object> workflowView(ScenarioWorkflow workflow) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflow.id());
        result.put("agentSessionId", workflow.agentSessionId());
        result.put("state", workflow.state());
        result.put("revision", workflow.revision());
        result.put("requirements", workflow.requirements());
        result.put("capabilityFingerprint", workflow.capabilityFingerprint());
        result.put("generatedFileSetDigest", workflow.generatedFileSetDigest());
        result.put("validation", workflow.validation());
        result.put("publicationReceiptDigest", workflow.publicationReceiptDigest());
        return result;
    }

    private void requireScope(McpCaller caller, String scope) {
        if (!caller.scopes().contains(scope)) {
            throw new ToolExecutionException("MCP_SCOPE_REQUIRED", scope);
        }
    }

    private static void requireFormElicitation(McpSyncServerExchange exchange) {
        if (exchange.getClientCapabilities() == null || exchange.getClientCapabilities().elicitation() == null
            || exchange.getClientCapabilities().elicitation().form() == null) {
            throw new ToolExecutionException("ELICITATION_CAPABILITY_REQUIRED",
                "The client must support MCP form elicitation");
        }
    }

    private static QaRequirementTopic topic(Map<String, Object> input) {
        return QaRequirementTopic.valueOf(text(input, "topic").toUpperCase(Locale.ROOT));
    }

    private static void requireWorkflowRevision(ScenarioWorkflow workflow, long expectedRevision) {
        if (workflow.revision() != expectedRevision) {
            throw new ToolExecutionException("WORKFLOW_VERSION_CONFLICT", workflow.id());
        }
    }

    private String schemaDigest(QaRequirementQuestionContract question) {
        return sha256(json(question.responseSchema()));
    }

    private static Map<String, Object> body(Map<String, Object> source, String... fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String field : fields) {
            if (source.containsKey(field) && source.get(field) != null) {
                body.put(field, source.get(field));
            }
        }
        return body;
    }

    private static Map<String, Object> swarmCreateBody(Map<String, Object> input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", text(input, "templateId"));
        body.put("idempotencyKey", text(input, "idempotencyKey"));
        body.put("autoPullImages", false);
        body.put("sutId", input.containsKey("sutId") ? text(input, "sutId") : null);
        body.put("variablesProfileId", input.containsKey("variablesProfileId") ? text(input, "variablesProfileId") : null);
        body.put("networkMode", "DIRECT");
        body.put("networkProfileId", null);
        return body;
    }

    private static Object require(Map<String, Object> input, String field) {
        if (!input.containsKey(field) || input.get(field) == null) {
            throw new ToolExecutionException("TOOL_INPUT_REQUIRED", field);
        }
        return input.get(field);
    }

    private static void rejectSourceFields(Map<String, Object> input) {
        if (input.containsKey("sourceName") || input.containsKey("sourceDigest")) {
            throw new ToolExecutionException("REQUIREMENT_SOURCE_FORBIDDEN",
                "sourceName/sourceDigest require USER_CONFIRMED_SOURCE");
        }
    }

    private static String text(Map<String, Object> input, String field) {
        String value = String.valueOf(require(input, field)).trim();
        if (value.isEmpty()) {
            throw new ToolExecutionException("TOOL_INPUT_REQUIRED", field);
        }
        return value;
    }

    private static String optionalText(Map<String, Object> input, String field) {
        return input.containsKey(field) ? text(input, field) : null;
    }

    private static String rawText(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (!(value instanceof String text)) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", field);
        }
        return text;
    }

    private static long number(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", field);
        }
    }

    private static boolean booleanValue(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (value instanceof Boolean result) {
            return result;
        }
        throw new ToolExecutionException("TOOL_INPUT_INVALID", field);
    }

    private static String segment(Map<String, Object> input, String field) {
        return UriUtils.encodePathSegment(text(input, field), StandardCharsets.UTF_8);
    }

    private static String query(Map<String, Object> input, String field) {
        return UriUtils.encodeQueryParam(text(input, field), StandardCharsets.UTF_8);
    }

    private static String queryOr(Map<String, Object> input, String field, String fallback) {
        return input.containsKey(field) ? query(input, field) : fallback;
    }

    private static String optionalQuery(Map<String, Object> input, String field) {
        return input.containsKey(field) ? "&" + field + "=" + query(input, field) : "";
    }

    private static String optionalSegmentQuery(Map<String, Object> input, String field) {
        return input.containsKey(field) ? "&" + field + "=" + segment(input, field) : "";
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ToolExecutionException("TOOL_RESULT_SERIALIZATION_FAILED", exception.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }
}
