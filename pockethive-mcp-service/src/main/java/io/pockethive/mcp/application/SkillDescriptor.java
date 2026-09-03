package io.pockethive.mcp.application;

/**
 * Responsibility: Carry immutable skill descriptor application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record SkillDescriptor(
    String id,
    String name,
    String description,
    String version,
    String contentDigest,
    String resourceUri,
    String markdown
) {
}
