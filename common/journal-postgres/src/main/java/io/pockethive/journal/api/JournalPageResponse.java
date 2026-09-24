package io.pockethive.journal.api;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: carry the existing journal page response without changing its wire shape.
 * Must not: query storage or choose runs.
 * Contract: RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
public record JournalPageResponse(
    List<Map<String, Object>> items,
    JournalCursor nextCursor,
    boolean hasMore) {}
