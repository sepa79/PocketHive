package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.mcp.domain.QaRequirementTopic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QaRequirementReviewContractTest {
    private static final String SHA = "sha256:" + "a".repeat(64);

    @Test
    void ownsSchemasAndNormalizesACompleteReviewInCanonicalTopicOrder() {
        List<Map<String, Object>> reversed = new ArrayList<>(confirmedAnswers("answer"));
        Collections.reverse(reversed);

        QaRequirementReviewContract contract = QaRequirementReviewContract.parse(
            reversed, "user narrative", SHA);

        assertThat(contract.answers()).extracting(answer -> answer.topic())
            .containsExactly(QaRequirementTopic.values());
        assertThat(contract.source().name()).isEqualTo("user narrative");
        assertThat(contract.canonicalPayload())
            .containsEntry("sourceName", "user narrative")
            .containsEntry("sourceDigest", SHA)
            .containsKey("answers");
        assertThat(contract.reviewMessage("{\"answers\":[]}"))
            .contains("Review every requirement below", "{\"answers\":[]}", "Reply ACCEPT");
        assertThat(QaRequirementReviewContract.answersSchema())
            .containsEntry("type", "array")
            .containsEntry("minItems", QaRequirementTopic.values().length)
            .containsEntry("maxItems", QaRequirementTopic.values().length)
            .containsKey("items");
        assertThat(QaRequirementReviewContract.responseSchema())
            .containsEntry("type", "object")
            .containsEntry("required", List.of("answers"))
            .containsKey("properties");
    }

    @Test
    void rejectsInvalidReviewShapesFieldsTopicsDispositionsAndAnswerLengths() {
        assertCode("QA_REVIEW_ANSWERS_INVALID",
            () -> QaRequirementReviewContract.parse("not-an-array", null, null));
        assertCode("QA_REVIEW_TOPICS_INCOMPLETE",
            () -> QaRequirementReviewContract.parse(List.of(), null, null));

        List<Object> nonObject = new ArrayList<>(userProvidedAnswers("answer"));
        nonObject.set(0, "not-an-object");
        assertCode("QA_REVIEW_ANSWER_INVALID",
            () -> QaRequirementReviewContract.parse(nonObject, null, null));

        assertInvalidAnswer("QA_REVIEW_ANSWER_FIELD_UNEXPECTED", Map.of("unexpected", "value"));
        assertInvalidAnswer("QA_REVIEW_TOPIC_INVALID", Map.of("topic", "NOT_A_TOPIC"));
        assertInvalidAnswer("REQUIREMENT_DISPOSITION_INVALID", Map.of("disposition", "NOT_A_DISPOSITION"));
        assertInvalidAnswer("REQUIREMENT_DISPOSITION_INVALID", Map.of("disposition", "UNKNOWN"));
        assertInvalidAnswer("REQUIREMENT_DISPOSITION_INVALID", Map.of("disposition", "DERIVED"));
        assertInvalidAnswer("TOOL_INPUT_REQUIRED", Map.of("answer", " "));
        assertInvalidAnswer("QA_REVIEW_ANSWER_INVALID", Map.of("answer", "a".repeat(20_001)));

        List<Map<String, Object>> exactBoundary = userProvidedAnswers("a".repeat(20_000));
        assertThatCode(() -> QaRequirementReviewContract.parse(exactBoundary, null, null))
            .doesNotThrowAnyException();
    }

    @Test
    void enforcesExactSourcePresenceForConfirmedAndUnconfirmedReviews() {
        assertCode("TOOL_INPUT_REQUIRED",
            () -> QaRequirementReviewContract.parse(confirmedAnswers("answer"), null, null));
        assertThatThrownBy(() -> QaRequirementReviewContract.parse(
            confirmedAnswers("answer"), "source", null))
            .isInstanceOf(ToolExecutionException.class)
            .hasMessage("sourceDigest");
        assertThatThrownBy(() -> QaRequirementReviewContract.parse(
            confirmedAnswers("answer"), null, SHA))
            .isInstanceOf(ToolExecutionException.class)
            .hasMessage("sourceName");
        assertCode("QA_REVIEW_SOURCE_INVALID",
            () -> QaRequirementReviewContract.parse(confirmedAnswers("answer"), "source", "invalid"));

        assertThat(QaRequirementReviewContract.parse(userProvidedAnswers("answer"), null, null).source()).isNull();
        assertThat(QaRequirementReviewContract.parse(userProvidedAnswers("answer"), null, null)
            .canonicalPayload()).doesNotContainKeys("sourceName", "sourceDigest");
        assertCode("REQUIREMENT_SOURCE_FORBIDDEN",
            () -> QaRequirementReviewContract.parse(userProvidedAnswers("answer"), "source", null));
        assertCode("REQUIREMENT_SOURCE_FORBIDDEN",
            () -> QaRequirementReviewContract.parse(userProvidedAnswers("answer"), null, SHA));
        assertCode("REQUIREMENT_SOURCE_FORBIDDEN",
            () -> QaRequirementReviewContract.parse(userProvidedAnswers("answer"), "source", SHA));
    }

    private static void assertInvalidAnswer(String code, Map<String, Object> replacement) {
        List<Map<String, Object>> answers = new ArrayList<>(userProvidedAnswers("answer"));
        Map<String, Object> invalid = new LinkedHashMap<>(answers.getFirst());
        invalid.putAll(replacement);
        answers.set(0, invalid);
        assertCode(code, () -> QaRequirementReviewContract.parse(answers, null, null));
    }

    private static List<Map<String, Object>> confirmedAnswers(String answer) {
        return answers("USER_CONFIRMED_SOURCE", answer);
    }

    private static List<Map<String, Object>> userProvidedAnswers(String answer) {
        return answers("USER_PROVIDED", answer);
    }

    private static List<Map<String, Object>> answers(String disposition, String answer) {
        return Arrays.stream(QaRequirementTopic.values())
            .map(topic -> Map.<String, Object>of(
                "topic", topic.name(), "disposition", disposition, "answer", answer))
            .toList();
    }

    private static void assertCode(String code, Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOf(ToolExecutionException.class)
            .extracting(exception -> ((ToolExecutionException) exception).code())
            .isEqualTo(code);
    }
}
