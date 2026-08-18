package io.pockethive.mcp.application;

import java.util.Map;
import java.util.TreeMap;

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
