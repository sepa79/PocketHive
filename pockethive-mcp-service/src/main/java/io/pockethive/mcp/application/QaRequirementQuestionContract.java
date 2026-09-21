package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.QaRequirementTopic;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: Publish the canonical QA requirement question and answer schemas.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

record QaRequirementQuestionContract(
    QaRequirementTopic topic,
    QaAnswerCaptureMode captureMode,
    String questionId,
    String message,
    Map<String, Object> responseSchema
) {
    private static final String DISPOSITION = "disposition";
    private static final String ANSWER = "answer";
    private static final String SOURCE_NAME = "sourceName";
    private static final String SOURCE_DIGEST = "sourceDigest";
    private static final Set<String> RESPONSE_FIELDS = Set.of(
        DISPOSITION, ANSWER, SOURCE_NAME, SOURCE_DIGEST);
    private static final Map<String, Object> RESPONSE_SCHEMA = buildResponseSchema();

    static QaRequirementQuestionContract forTopic(QaRequirementTopic topic, QaAnswerCaptureMode captureMode) {
        return new QaRequirementQuestionContract(
            topic,
            captureMode,
            captureMode.questionId(topic),
            question(topic),
            RESPONSE_SCHEMA);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> responseFieldSchema(String field) {
        Object schema = ((Map<String, Object>) RESPONSE_SCHEMA.get("properties")).get(field);
        if (!(schema instanceof Map<?, ?> property)) {
            throw new IllegalArgumentException("Unknown QA response field: " + field);
        }
        return (Map<String, Object>) property;
    }

    void validateResponseFields(Map<String, Object> content) {
        content.keySet().stream()
            .filter(field -> !RESPONSE_FIELDS.contains(field))
            .findFirst()
            .ifPresent(field -> {
                throw new ToolExecutionException("QA_RESPONSE_FIELD_UNEXPECTED", field);
            });
    }

    private static Map<String, Object> buildResponseSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(DISPOSITION, ordered(
            "type", "string",
            "enum", List.of("USER_PROVIDED", "USER_CONFIRMED_SOURCE", "NOT_APPLICABLE")));
        properties.put(ANSWER, ordered(
            "type", "string",
            "minLength", 1,
            "maxLength", 20_000));
        properties.put(SOURCE_NAME, ordered(
            "type", "string",
            "minLength", 1,
            "maxLength", 2_048));
        properties.put(SOURCE_DIGEST, ordered(
            "type", "string",
            "pattern", "^sha256:[0-9a-f]{64}$"));
        return ordered(
            "type", "object",
            "properties", Collections.unmodifiableMap(properties),
            "required", List.of(DISPOSITION, ANSWER),
            "additionalProperties", false);
    }

    private static Map<String, Object> ordered(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String question(QaRequirementTopic topic) {
        return switch (topic) {
            case GOAL_AND_RISK -> "What goal, risks, scope, and out-of-scope behaviour must this test cover?";
            case SUT_AND_ENDPOINTS -> "Which systems under test, endpoints, protocols, owners, and environments are in scope?";
            case JOURNEYS_SCHEMAS_AND_EXPECTATIONS -> "Which journeys, example tests, schemas, contracts, and expected outcomes apply?";
            case SLA_AND_STOPPING -> "Which SLAs, thresholds, error budgets, stopping criteria, and abort conditions apply?";
            case LOAD_PROFILE_AND_TRAFFIC_SHAPE -> "What load, concurrency, arrival model, duration, ramping, and traffic shape are required?";
            case TEST_DATA_STRATEGY -> "What test-data sources, profiles, storage, volumes, privacy, retention, Redis/CSV use, and cleanup are required?";
            case AUTHENTICATION_AND_SECRETS -> "Which authentication profiles and approved secret references are required? Do not provide secret values.";
            case SETUP_TEARDOWN_AND_DEPENDENCIES -> "What setup, teardown, reset, seeding, and dependency requirements apply?";
            case BACKGROUND_TRAFFIC_AND_ISOLATION -> "Is background traffic required, and how must it be isolated from foreground traffic?";
            case ORACLES_OBSERVABILITY_AND_TRIAGE -> "Which oracles, negative cases, observability, diagnostics, and triage evidence are required?";
            case REPORTING_TRACEABILITY_AND_RETENTION -> "Which reporting, traceability, ownership, provenance, and retention requirements apply?";
            case SAFETY_GOVERNANCE_AND_ABORT -> "Which safety limits, approvals, governance constraints, and abort rules apply?";
        };
    }
}
