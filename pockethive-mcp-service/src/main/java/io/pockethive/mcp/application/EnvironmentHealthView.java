package io.pockethive.mcp.application;

import java.time.Instant;
import java.util.List;

/**
 * Responsibility: Carry immutable environment health view application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record EnvironmentHealthView(
    EnvironmentHealthStatus status,
    List<EnvironmentServiceHealth> services,
    Instant observedAt
) {
}
