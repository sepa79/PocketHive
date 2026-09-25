package io.pockethive.journal.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Responsibility: expose read-only journal event pages, timelines and stored run/capture observations.
 * Must not: choose registry state, authorize callers or mutate journal data.
 * Contract: RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
public interface JournalEventQueries {
    JournalPageResponse hivePage(String swarmId, String runId, String correlationId,
        Instant beforeTs, Long beforeId, int limit);

    JournalPageResponse swarmPage(String swarmId, String runId, String correlationId,
        String severity, Instant beforeTs, Long beforeId, int limit);

    JournalPageResponse archivedSwarmPage(UUID captureId, String swarmId, String runId,
        String correlationId, String severity, Instant beforeTs, Long beforeId, int limit);

    List<Map<String, Object>> swarmTimeline(String swarmId, String runId, String severity);

    List<Map<String, Object>> archivedSwarmTimeline(UUID captureId, String swarmId, String runId,
        String severity);

    /** Existing best-effort lookup: latest live run, then pinned capture; null if unavailable. */
    String latestSwarmRun(String swarmId);

    /** Existing best-effort capture lookup; null when absent or unavailable. */
    UUID pinnedSwarmCapture(String swarmId, String runId);
}
