package io.pockethive.mcp.application;

/**
 * Responsibility: describe bundled canonical knowledge for application projections. Must not:
 * execute tools or decide owner-service outcomes. Contract: RESP-MCP-KNOWLEDGE-PROJECTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-knowledge-projection.
 */
public interface KnowledgeDocumentSource {
  java.util.List<KnowledgeDocument> documents();
}
