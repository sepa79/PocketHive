package io.pockethive.mcp.domain;

/**
 * Responsibility: Model the ScenarioWorkflowState MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

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
