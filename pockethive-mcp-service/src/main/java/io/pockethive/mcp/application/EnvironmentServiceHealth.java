package io.pockethive.mcp.application;

import java.net.URI;
import java.time.Instant;

/**
 * Responsibility: Carry immutable environment service health application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record EnvironmentServiceHealth(
    String id,
    String name,
    URI endpoint,
    EnvironmentServiceStatus status,
    Instant observedAt
) {
}
