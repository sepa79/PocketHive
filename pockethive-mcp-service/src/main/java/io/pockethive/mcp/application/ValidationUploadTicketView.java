package io.pockethive.mcp.application;

import java.net.URI;
import java.time.Instant;

/** Stable client projection for a validation upload ticket. */
public record ValidationUploadTicketView(String ticketId, URI uploadUrl, Instant expiresAt) {
}
