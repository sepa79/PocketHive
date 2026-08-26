package io.pockethive.mcp.application;

/**
 * Responsibility: Carry immutable publication upload outcome application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record PublicationUploadOutcome(PublicationAttemptView publicationAttempt) implements UploadOutcome {
}
