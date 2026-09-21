package io.pockethive.mcp.domain;

/**
 * Responsibility: Model the RequirementDisposition MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public enum RequirementDisposition {
    USER_PROVIDED,
    USER_CONFIRMED_SOURCE,
    NOT_APPLICABLE,
    UNKNOWN,
    DERIVED
}
