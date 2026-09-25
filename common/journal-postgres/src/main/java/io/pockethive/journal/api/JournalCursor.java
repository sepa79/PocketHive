package io.pockethive.journal.api;

import java.time.Instant;

/**
 * Responsibility: carry the existing timestamp/id journal page cursor.
 * Must not: select runs or query storage.
 * Contract: RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
public record JournalCursor(Instant ts, long id) {}
