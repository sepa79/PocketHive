package io.pockethive.journal.api;

import java.time.Instant;

/**
 * Responsibility: project a merged run into the existing per-swarm response shape.
 * Must not: independently merge or mutate run state.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries.
 */
public record JournalRunSummary(String runId, Instant firstTs, Instant lastTs, long entries, boolean pinned) {
    public static JournalRunSummary from(SwarmRunSummary run) {
        return new JournalRunSummary(run.runId(), run.firstTs(), run.lastTs(), run.entries(), run.pinned());
    }
}
