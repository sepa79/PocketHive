package io.pockethive.mcp.domain;

public final class WorkflowRuleViolation extends RuntimeException {
    public WorkflowRuleViolation(String code) {
        super(code);
    }
}
