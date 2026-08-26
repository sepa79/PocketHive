package io.pockethive.mcp.application;

import java.net.URI;
import java.time.Instant;

/**
 * Responsibility: Carry immutable validation upload ticket view application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

/** Stable client projection for a validation upload ticket. */
public record ValidationUploadTicketView(
    String ticketId,
    URI uploadUrl,
    String uploadCapability,
    Instant expiresAt
) {
}
