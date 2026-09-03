package io.pockethive.mcp.application;

import java.util.Map;

/**
 * Responsibility: Carry immutable swarm readiness result application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record SwarmReadinessResult(
    boolean ready,
    String swarmId,
    Map<String, Object> totals,
    String swarmStatus,
    int polls
) {
}
