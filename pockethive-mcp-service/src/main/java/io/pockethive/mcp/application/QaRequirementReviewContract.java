package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.ConfirmedSource;
import io.pockethive.mcp.domain.QaRequirementTopic;
import io.pockethive.mcp.domain.RequirementDisposition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: Prepare and verify the canonical compact QA review contract.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

final class QaRequirementReviewContract {
    static final String REVIEW_ID = "compact-review/all-topics";
    private static final int MAX_ANSWER_LENGTH = 20_000;
    private static final Set<String> ANSWER_FIELDS = Set.of("topic", "disposition", "answer");
    private static final Set<RequirementDisposition> ACCEPTED_DISPOSITIONS = Set.of(
        RequirementDisposition.USER_PROVIDED,
        RequirementDisposition.USER_CONFIRMED_SOURCE,
        RequirementDisposition.NOT_APPLICABLE);
    private static final Map<String, Object> ANSWERS_SCHEMA = buildAnswersSchema();
    private static final Map<String, Object> RESPONSE_SCHEMA = buildResponseSchema();

    private final List<ReviewAnswer> answers;
    private final ConfirmedSource source;

    private QaRequirementReviewContract(List<ReviewAnswer> answers, ConfirmedSource source) {
        this.answers = List.copyOf(answers);
        this.source = source;
    }

    static QaRequirementReviewContract parse(Object value, String sourceName, String sourceDigest) {
        if (!(value instanceof List<?> items)) {
            throw new ToolExecutionException("QA_REVIEW_ANSWERS_INVALID", "answers must be an array");
        }
        if (items.size() != QaRequirementTopic.values().length) {
            throw new ToolExecutionException("QA_REVIEW_TOPICS_INCOMPLETE",
                "the compact review requires every canonical QA topic exactly once");
        }
        List<ReviewAnswer> answers = new ArrayList<>();
        EnumSet<QaRequirementTopic> topics = EnumSet.noneOf(QaRequirementTopic.class);
        for (Object item : items) {
            ReviewAnswer answer = parseAnswer(item);
            if (!topics.add(answer.topic())) {
                throw new ToolExecutionException("QA_REVIEW_TOPIC_DUPLICATE", answer.topic().name());
            }
            answers.add(answer);
        }
        answers.sort(java.util.Comparator.comparingInt(answer -> answer.topic().ordinal()));
        boolean sourceRequired = answers.stream()
            .anyMatch(answer -> answer.disposition() == RequirementDisposition.USER_CONFIRMED_SOURCE);
        ConfirmedSource source = source(sourceName, sourceDigest, sourceRequired);
        return new QaRequirementReviewContract(answers, source);
    }

    static Map<String, Object> answersSchema() {
        return ANSWERS_SCHEMA;
    }

    static Map<String, Object> responseSchema() {
        return RESPONSE_SCHEMA;
    }

    List<ReviewAnswer> answers() {
        return answers;
    }

    ConfirmedSource source() {
        return source;
    }

    Map<String, Object> canonicalPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (source != null) {
            payload.put("sourceName", source.name());
            payload.put("sourceDigest", source.digest());
        }
        payload.put("answers", answers.stream().map(ReviewAnswer::asMap).toList());
        return Collections.unmodifiableMap(payload);
    }

    String reviewMessage(String canonicalJson) {
        return """
            Review every requirement below. Reply ACCEPT only if this exact structured brief matches your intent. \
            If anything is wrong or unknown, describe the edits instead; the agent must prepare a new review.

            %s
            """.formatted(canonicalJson).strip();
    }

    private static ReviewAnswer parseAnswer(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ToolExecutionException("QA_REVIEW_ANSWER_INVALID", "each answer must be an object");
        }
        raw.keySet().stream()
            .map(String::valueOf)
            .filter(field -> !ANSWER_FIELDS.contains(field))
            .findFirst()
            .ifPresent(field -> {
                throw new ToolExecutionException("QA_REVIEW_ANSWER_FIELD_UNEXPECTED", field);
            });
        QaRequirementTopic topic;
        RequirementDisposition disposition;
        try {
            topic = QaRequirementTopic.valueOf(requiredText(raw, "topic").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("QA_REVIEW_TOPIC_INVALID", String.valueOf(raw.get("topic")));
        }
        try {
            disposition = RequirementDisposition.valueOf(
                requiredText(raw, "disposition").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("REQUIREMENT_DISPOSITION_INVALID",
                String.valueOf(raw.get("disposition")));
        }
        if (!ACCEPTED_DISPOSITIONS.contains(disposition)) {
            throw new ToolExecutionException("REQUIREMENT_DISPOSITION_INVALID", disposition.name());
        }
        String answer = requiredText(raw, "answer");
        if (answer.length() > MAX_ANSWER_LENGTH) {
            throw new ToolExecutionException("QA_REVIEW_ANSWER_INVALID", "answer exceeds 20000 characters");
        }
        return new ReviewAnswer(topic, disposition, answer);
    }

    private static ConfirmedSource source(String name, String digest, boolean required) {
        boolean hasName = name != null;
        boolean hasDigest = digest != null;
        if (required && (!hasName || !hasDigest)) {
            throw new ToolExecutionException("TOOL_INPUT_REQUIRED", hasName ? "sourceDigest" : "sourceName");
        }
        if (!required && (hasName || hasDigest)) {
            throw new ToolExecutionException("REQUIREMENT_SOURCE_FORBIDDEN",
                "sourceName/sourceDigest require USER_CONFIRMED_SOURCE");
        }
        if (!required) {
            return null;
        }
        try {
            return new ConfirmedSource(name, digest);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ToolExecutionException("QA_REVIEW_SOURCE_INVALID", exception.getMessage());
        }
    }

    private static String requiredText(Map<?, ?> input, String field) {
        Object value = input.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ToolExecutionException("TOOL_INPUT_REQUIRED", field);
        }
        return text.trim();
    }

    private static Map<String, Object> buildAnswersSchema() {
        return Map.of(
            "type", "array",
            "minItems", QaRequirementTopic.values().length,
            "maxItems", QaRequirementTopic.values().length,
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "topic", Map.of(
                        "type", "string",
                        "enum", java.util.Arrays.stream(QaRequirementTopic.values()).map(Enum::name).toList()),
                    "disposition", QaRequirementQuestionContract.responseFieldSchema("disposition"),
                    "answer", QaRequirementQuestionContract.responseFieldSchema("answer")),
                "required", List.of("topic", "disposition", "answer"),
                "additionalProperties", false));
    }

    private static Map<String, Object> buildResponseSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourceName", QaRequirementQuestionContract.responseFieldSchema("sourceName"));
        properties.put("sourceDigest", QaRequirementQuestionContract.responseFieldSchema("sourceDigest"));
        properties.put("answers", ANSWERS_SCHEMA);
        return Map.of(
            "type", "object",
            "properties", Collections.unmodifiableMap(properties),
            "required", List.of("answers"),
            "additionalProperties", false);
    }

    record ReviewAnswer(QaRequirementTopic topic, RequirementDisposition disposition, String answer) {
        private Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("topic", topic.name());
            value.put("disposition", disposition.name());
            value.put("answer", answer);
            return Collections.unmodifiableMap(value);
        }
    }
}
