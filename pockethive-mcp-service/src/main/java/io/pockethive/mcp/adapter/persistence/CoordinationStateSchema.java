package io.pockethive.mcp.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CoordinationStateSchema {
    static final int CURRENT_VERSION = 2;
    private static final int LEGACY_RECEIPT_VERSION = 1;

    Migration migrate(JsonNode encodedState) {
        if (!(encodedState instanceof ObjectNode root)
            || root.path("schemaVersion").asInt(Integer.MIN_VALUE) != LEGACY_RECEIPT_VERSION) {
            return new Migration(encodedState, false);
        }
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

        JsonNode ticketNode = coordination.path("tickets");
        if (!(ticketNode instanceof ObjectNode tickets)) {
            throw new IllegalStateException("MCP_STATE_CORRUPT");
        }
        List<String> invalidTicketIds = new ArrayList<>();
        tickets.fields().forEachRemaining(entry -> {
            JsonNode receiptId = entry.getValue().path("validationReceiptId");
            if (receiptId.isTextual() && invalidReceiptIds.contains(receiptId.textValue())) {
                invalidTicketIds.add(entry.getKey());
            }
        });
        invalidTicketIds.forEach(tickets::remove);
        root.put("schemaVersion", CURRENT_VERSION);
        return new Migration(root, true);
    }

    record Migration(JsonNode encodedState, boolean migrated) {
    }
}
