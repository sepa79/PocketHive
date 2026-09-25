package io.pockethive.journal.api;

import java.time.Instant;
import java.util.List;

/**
 * Responsibility: expose read-only stored run lists and metadata summaries.
 * Must not: authorize requests, own registry state or write journals.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries.
 */
public interface JournalRunQueries {
    List<JournalRunSummary> swarmRuns(String swarmId);
    List<SwarmRunSummary> allSwarmRuns(int limit, boolean pinnedOnly, Instant afterTs);
    SwarmRunSummary metadataSummary(String swarmId, String runId);
}
