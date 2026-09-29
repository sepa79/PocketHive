package io.pockethive.journal.postgres;

import java.time.Instant;
import java.util.Map;

/**
 * Responsibility: carry one decoded SQL event and its stable page position.
 * Must not: select or mutate events.
 * Contract: RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
record JournalEventRow(long id, Instant timestamp, Map<String, Object> entry) {}
