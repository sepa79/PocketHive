package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.mcp.domain.QaRequirementTopic;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QaRequirementQuestionContractTest {
    @Test
    void ownsEveryCanonicalQuestionSchemaAndCaptureSpecificQuestionId() {
        assertThat(QaAnswerCaptureMode.values())
            .containsExactly(QaAnswerCaptureMode.MCP_FORM, QaAnswerCaptureMode.AGENT_MEDIATED,
                QaAnswerCaptureMode.COMPACT_REVIEW);
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            QaRequirementQuestionContract form = QaRequirementQuestionContract.forTopic(
                topic, QaAnswerCaptureMode.MCP_FORM);
            QaRequirementQuestionContract mediated = QaRequirementQuestionContract.forTopic(
                topic, QaAnswerCaptureMode.AGENT_MEDIATED);

            assertThat(form.message()).isNotBlank().isEqualTo(mediated.message());
            assertThat(form.responseSchema()).isSameAs(mediated.responseSchema());
            assertThat(form.questionId()).isEqualTo("mcp-form/" + topic.name().toLowerCase(Locale.ROOT));
            assertThat(mediated.questionId()).isEqualTo("agent-mediated/" + topic.name().toLowerCase(Locale.ROOT));
        }

        assertThat(QaRequirementQuestionContract.responseFieldSchema("disposition"))
            .containsEntry("type", "string")
            .containsEntry("enum", List.of("USER_PROVIDED", "USER_CONFIRMED_SOURCE", "NOT_APPLICABLE"));
        assertThat(QaRequirementQuestionContract.responseFieldSchema("answer"))
            .containsEntry("minLength", 1)
            .containsEntry("maxLength", 20_000);
        assertThat(QaRequirementQuestionContract.responseFieldSchema("sourceName"))
            .containsEntry("maxLength", 2_048);
        assertThat(QaRequirementQuestionContract.responseFieldSchema("sourceDigest"))
            .containsEntry("pattern", "^sha256:[0-9a-f]{64}$");
        assertThatThrownBy(() -> QaRequirementQuestionContract.responseFieldSchema("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown QA response field: unknown");
    }

    @Test
    void rejectsUnexpectedResponseFieldsAndAllowsTheCanonicalFields() {
        QaRequirementQuestionContract question = QaRequirementQuestionContract.forTopic(
            QaRequirementTopic.GOAL_AND_RISK, QaAnswerCaptureMode.AGENT_MEDIATED);

        assertThatCode(() -> question.validateResponseFields(Map.of(
            "disposition", "USER_PROVIDED",
            "answer", "Explicit answer",
            "sourceName", "source",
            "sourceDigest", "sha256:" + "a".repeat(64))))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> question.validateResponseFields(Map.of("unexpected", "value")))
            .isInstanceOf(ToolExecutionException.class)
            .extracting(exception -> ((ToolExecutionException) exception).code())
            .isEqualTo("QA_RESPONSE_FIELD_UNEXPECTED");
    }
}
