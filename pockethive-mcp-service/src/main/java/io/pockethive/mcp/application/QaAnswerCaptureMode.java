package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.QaRequirementTopic;
import java.util.Locale;

public enum QaAnswerCaptureMode {
    MCP_FORM("mcp-form"),
    AGENT_MEDIATED("agent-mediated"),
    COMPACT_REVIEW("compact-review");

    private final String questionIdNamespace;

    QaAnswerCaptureMode(String questionIdNamespace) {
        this.questionIdNamespace = questionIdNamespace;
    }

    String questionId(QaRequirementTopic topic) {
        return questionIdNamespace + "/" + topic.name().toLowerCase(Locale.ROOT);
    }
}
