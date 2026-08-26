package io.pockethive.mcp.application;

import static io.pockethive.mcp.application.ToolArguments.number;
import static io.pockethive.mcp.application.ToolArguments.optionalText;
import static io.pockethive.mcp.application.ToolArguments.rawText;
import static io.pockethive.mcp.application.ToolArguments.require;
import static io.pockethive.mcp.application.ToolArguments.text;

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
import io.pockethive.mcp.domain.ElicitationAction;
import io.pockethive.mcp.domain.QaRequirementTopic;
import io.pockethive.mcp.domain.RequirementAnswer;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
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
import org.springframework.stereotype.Component;

/**
 * Responsibility: Execute the QA requirement-capture and scenario-workflow authoring lifecycle.
 * Must not: Manage agent-session lifecycle, publish bundles, or map owner-service tool families.
 * Contract: docs/mcp/README.md.
 */
@Component
final class ScenarioWorkflowToolExecutor {
    private static final String SCENARIO_PREFIX = "/scenario-manager";

    private final WorkflowAccess workflows;
    private final OwnerApiPort owners;
    private final PocketHiveMcpProperties properties;
    private final ObjectMapper mapper;
    private final CoordinationStateRepository state;
    private final WorkflowProjection projection;
    private final Clock clock;
    private final Map<McpToolId, ToolAction> actions;

    ScenarioWorkflowToolExecutor(WorkflowAccess workflows, OwnerApiPort owners,
                                 PocketHiveMcpProperties properties, ObjectMapper mapper,
                                 CoordinationStateRepository state, WorkflowProjection projection,
                                 Clock clock) {
        this.workflows = workflows;
        this.owners = owners;
        this.properties = properties;
        this.mapper = mapper;
        this.state = state;
        this.projection = projection;
        this.clock = clock;
        this.actions = Map.ofEntries(
            Map.entry(McpToolId.SCENARIO_WORKFLOW_CREATE,
                (exchange, caller, input) -> create(input, caller)),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_GET, (exchange, caller, input) -> projection.workflow(
                workflows.requireWorkflow(text(input, "workflowId"), caller))),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_ANSWER, this::answer),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_QUESTION,
                (exchange, caller, input) -> question(input, caller)),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_ANSWER_SUBMIT, this::submitAnswer),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_REVIEW_PREPARE,
                (exchange, caller, input) -> prepareReview(input, caller)),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_REVIEW_SUBMIT, this::submitReview),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_GENERATE,
                (exchange, caller, input) -> generate(input, caller)),
            Map.entry(McpToolId.SCENARIO_WORKFLOW_CANCEL,
                (exchange, caller, input) -> cancel(input, caller)));
    }

    boolean supports(McpToolId toolId) {
        return actions.containsKey(toolId);
    }

    Object execute(McpToolId toolId, McpSyncServerExchange exchange, McpCaller caller,
                   Map<String, Object> input) {
        ToolAction action = actions.get(toolId);
        if (action == null) {
            throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId.externalName());
        }
        return action.execute(exchange, caller, input);
    }

    private Object create(Map<String, Object> input, McpCaller caller) {
        AgentSession session = workflows.requireSession(text(input, "agentSessionId"), caller);
        String id = "wf-" + UUID.randomUUID();
        session.addWorkflow(number(input, "expectedSessionRevision"), id, properties.maxWorkflowsPerSession());
        ScenarioWorkflow workflow = ScenarioWorkflow.create(id, session.id(), caller.principal());
        state.createWorkflow(session, workflow);
        return projection.workflow(workflow);
    }

    private Object answer(McpSyncServerExchange exchange, McpCaller caller, Map<String, Object> input) {
        requireFormElicitation(exchange);
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
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

    private Object question(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
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

    private Object submitAnswer(McpSyncServerExchange exchange, McpCaller caller, Map<String, Object> input) {
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
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

    private Object prepareReview(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
        requireWorkflowRevision(workflow, number(input, "expectedRevision"));
        QaRequirementReviewContract review = reviewContract(input);
        return reviewView(workflow, review);
    }

    private Object submitReview(McpSyncServerExchange exchange, McpCaller caller,
                                Map<String, Object> input) {
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
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
        return projection.workflow(workflow);
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

    private Map<String, Object> reviewView(ScenarioWorkflow workflow, QaRequirementReviewContract review) {
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
        return projection.workflow(workflow);
    }

    private Object generate(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
        long expectedRevision = number(input, "expectedRevision");
        List<Map<String, Object>> files = fileList(require(input, "files"));
        String fileDigest = sha256(json(files));
        Object capability = owners.get(SCENARIO_PREFIX + "/api/authoring-contract/fingerprint");
        workflow.readyToGenerate(expectedRevision, new CapabilityFingerprint(sha256(json(capability)), clock.instant()));
        workflow.generated(workflow.revision(), fileDigest);
        state.saveWorkflow(workflow, files);
        Map<String, Object> view = new LinkedHashMap<>(projection.workflow(workflow));
        view.put("files", files);
        view.put("fileSetDigest", fileDigest);
        return view;
    }

    private Object cancel(Map<String, Object> input, McpCaller caller) {
        ScenarioWorkflow workflow = workflows.requireMutableWorkflow(text(input, "workflowId"), caller);
        workflow.cancel(number(input, "expectedRevision"));
        state.saveWorkflowAndRemoveGeneratedFiles(workflow);
        return projection.workflow(workflow);
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

    private static void rejectSourceFields(Map<String, Object> input) {
        if (input.containsKey("sourceName") || input.containsKey("sourceDigest")) {
            throw new ToolExecutionException("REQUIREMENT_SOURCE_FORBIDDEN",
                "sourceName/sourceDigest require USER_CONFIRMED_SOURCE");
        }
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

    @FunctionalInterface
    private interface ToolAction {
        Object execute(McpSyncServerExchange exchange, McpCaller caller, Map<String, Object> input);
    }
}
