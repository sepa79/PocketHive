package io.pockethive.journal.postgres;

import java.time.Instant;

/**
 * Normalized row representation for inserting into {@code journal_event}.
 * <p>
 * JSON fields ({@code dataJson/rawJson/extraJson}) are expected to be serialized already.
 */
public record PostgresJournalRecord(
    Instant timestamp,
    String scope,
    String swarmId,
    String runId,
    String scopeRole,
    String scopeInstance,
    String severity,
    String direction,
    String kind,
    String type,
    String origin,
    String correlationId,
    String idempotencyKey,
    String routingKey,
    String dataJson,
    String rawJson,
    String extraJson) {
}

