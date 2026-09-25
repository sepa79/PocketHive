package io.pockethive.orchestrator.app;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: expose read-only access to swarm journal files and directory observations.
 * Must not: choose the active run, modify journals or reconstruct paths in callers.
 * Contract: RESP-SWARM-FILE-JOURNAL — docs/architecture/runtime-responsibilities.md#resp-swarm-file-journal.
 */
public interface SwarmJournalFiles {
    /** Returns the latest directory by modification time, or null when unavailable. */
    String latestRunDirectory(String swarmId);

    /**
     * Reads entries in append order, optionally filtering by the normalized severity.
     * Returns null for a missing/unreadable file and an empty list for an empty journal.
     */
    List<Map<String, Object>> read(String swarmId, String runId, String severityFilter);
}
