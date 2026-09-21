package io.pockethive.mcp.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: Model the AgentSessionSnapshot MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record AgentSessionSnapshot(
    String id,
    PrincipalKey principal,
    Instant createdAt,
    Instant expiresAt,
    List<String> workflowIds,
    AgentSessionState state,
    Instant closedAt,
    long revision
) {
    public AgentSessionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        workflowIds = List.copyOf(workflowIds);
        Objects.requireNonNull(state, "state");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
