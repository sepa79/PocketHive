package io.pockethive.mcp.application;

import java.util.Map;

public record SwarmReadinessResult(
    boolean ready,
    String swarmId,
    Map<String, Object> totals,
    String swarmStatus,
    int polls
) {
}
