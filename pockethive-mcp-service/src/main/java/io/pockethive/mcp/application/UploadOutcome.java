package io.pockethive.mcp.application;

/**
 * Responsibility: Define the closed upload outcome application contract.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public sealed interface UploadOutcome permits ValidationUploadOutcome, PublicationUploadOutcome {
}
