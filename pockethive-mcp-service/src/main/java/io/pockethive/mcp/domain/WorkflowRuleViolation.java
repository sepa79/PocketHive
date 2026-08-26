package io.pockethive.mcp.domain;

/**
 * Responsibility: Model the WorkflowRuleViolation MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public final class WorkflowRuleViolation extends RuntimeException {
    public WorkflowRuleViolation(String code) {
        super(code);
    }
}
