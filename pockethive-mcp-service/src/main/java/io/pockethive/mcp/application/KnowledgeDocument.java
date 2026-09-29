package io.pockethive.mcp.application;

/**
 * Responsibility: describe bundled canonical knowledge for application projections. Must not:
 * execute tools or decide owner-service outcomes. Contract: RESP-MCP-KNOWLEDGE-PROJECTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-knowledge-projection.
 */
public record KnowledgeDocument(
    String uri, String title, String sourcePath, String digest, String markdown) {}
