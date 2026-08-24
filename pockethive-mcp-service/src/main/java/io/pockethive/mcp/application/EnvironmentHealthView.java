package io.pockethive.mcp.application;

import java.time.Instant;
import java.util.List;

public record EnvironmentHealthView(
    EnvironmentHealthStatus status,
    List<EnvironmentServiceHealth> services,
    Instant observedAt
) {
}
