package io.pockethive.mcp.application;

import java.net.URI;
import java.time.Instant;

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
