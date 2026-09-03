package io.pockethive.mcp.application;

import java.net.URI;

/**
 * Responsibility: Carry immutable environment health target application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record EnvironmentHealthTarget(
    String id,
    String name,
    URI endpointPath,
    String probePath,
    EnvironmentHealthContract contract
) {
}
