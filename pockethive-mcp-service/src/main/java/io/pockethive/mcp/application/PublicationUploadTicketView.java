package io.pockethive.mcp.application;

import java.net.URI;
import java.time.Instant;

/**
 * Responsibility: Carry immutable publication upload ticket view application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

/** Stable client projection for an explicit publication upload ticket. */
public record PublicationUploadTicketView(
    String ticketId,
    URI uploadUrl,
    String uploadCapability,
    Instant expiresAt,
    String attemptId,
    String validationReceiptId,
    PublicationMode mode,
    String scenarioId
) {
}
