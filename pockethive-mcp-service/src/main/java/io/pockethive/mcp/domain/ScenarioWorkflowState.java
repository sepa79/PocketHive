package io.pockethive.mcp.domain;

public enum ScenarioWorkflowState {
    DISCOVERING,
    REVIEW_REQUIRED,
    READY_TO_GENERATE,
    GENERATED,
    VALIDATED,
    PUBLISHED,
    BLOCKED,
    CANCELLED
}
