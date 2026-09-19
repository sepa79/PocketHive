package io.pockethive.mcp.application;

import java.util.Map;
import java.util.TreeMap;

/**
 * Responsibility: Carry immutable upload coordination snapshot application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record UploadCoordinationSnapshot(
    Map<String, UploadTicketSnapshot> tickets,
    Map<String, BundleValidationReceipt> receipts,
    Map<String, PublicationAttemptSnapshot> attempts
) {
    public UploadCoordinationSnapshot {
        tickets = Map.copyOf(new TreeMap<>(tickets));
        receipts = Map.copyOf(new TreeMap<>(receipts));
        attempts = Map.copyOf(new TreeMap<>(attempts));
    }

    public static UploadCoordinationSnapshot empty() {
        return new UploadCoordinationSnapshot(Map.of(), Map.of(), Map.of());
    }
}
