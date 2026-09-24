package io.pockethive.journal.api;

import java.time.Instant;
import java.util.List;

/**
 * Responsibility: carry the existing deployment-wide run summary projection.
 * Must not: write run state or merge observations.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries.
 */
public record SwarmRunSummary(String swarmId, String runId, Instant firstTs, Instant lastTs,
    long entries, boolean pinned, String scenarioId, String testPlan, List<String> tags, String description) {}
