package io.pockethive.mcp.application;

/**
 * Responsibility: Define the environment health probe application port.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

@FunctionalInterface
public interface EnvironmentHealthProbePort {
    boolean healthy(EnvironmentHealthTarget target);
}
