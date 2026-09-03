package io.pockethive.mcp.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/**
 * Responsibility: Migrate persisted coordination documents to the canonical schema version.
 * Must not: Own domain transitions or expose persistence details through public contracts.
 * Contract: docs/mcp/README.md.
 */

final class CoordinationStateSchema {
    static final int CURRENT_VERSION = 3;
    private static final int LEGACY_RECEIPT_VERSION = 1;
    private static final int LEGACY_UPLOAD_AUTHENTICATION_VERSION = 2;

    Migration migrate(JsonNode encodedState) {
        if (!(encodedState instanceof ObjectNode root)) {
            return new Migration(encodedState, false);
        }
        int version = root.path("schemaVersion").asInt(Integer.MIN_VALUE);
        if (version != LEGACY_RECEIPT_VERSION && version != LEGACY_UPLOAD_AUTHENTICATION_VERSION) {
            return new Migration(encodedState, false);
        }
        if (version == LEGACY_RECEIPT_VERSION) {
            removeReceiptsWithoutOwnerIdentity(root);
        }
        invalidateLegacyUploadTickets(root);
        root.put("schemaVersion", CURRENT_VERSION);
        return new Migration(root, true);
    }

    private static void removeReceiptsWithoutOwnerIdentity(ObjectNode root) {
        JsonNode coordination = root.path("uploadCoordination");
        JsonNode receiptNode = coordination.path("receipts");
        if (!(receiptNode instanceof ObjectNode receipts)) {
            throw new IllegalStateException("MCP_STATE_CORRUPT");
        }
        Set<String> invalidReceiptIds = new HashSet<>();
        receipts.fields().forEachRemaining(entry -> {
            JsonNode scenarioName = entry.getValue().path("scenarioName");
            if (!scenarioName.isTextual() || scenarioName.textValue().isBlank()) {
                invalidReceiptIds.add(entry.getKey());
            }
        });
        invalidReceiptIds.forEach(receipts::remove);

        if (!(coordination.path("tickets") instanceof ObjectNode)) {
            throw new IllegalStateException("MCP_STATE_CORRUPT");
        }
    }

    private static void invalidateLegacyUploadTickets(ObjectNode root) {
        JsonNode coordination = root.path("uploadCoordination");
        JsonNode ticketNode = coordination.path("tickets");
        JsonNode attemptNode = coordination.path("attempts");
        if (!(ticketNode instanceof ObjectNode tickets) || !(attemptNode instanceof ObjectNode attempts)) {
            throw new IllegalStateException("MCP_STATE_CORRUPT");
        }
        Set<String> invalidAttemptIds = new HashSet<>();
        tickets.elements().forEachRemaining(ticket -> {
            JsonNode attemptId = ticket.path("attemptId");
            if (attemptId.isTextual() && !attemptId.textValue().isBlank()) {
                invalidAttemptIds.add(attemptId.textValue());
            }
        });
        tickets.removeAll();
        invalidAttemptIds.forEach(attempts::remove);
    }

    record Migration(JsonNode encodedState, boolean migrated) {
    }
}
