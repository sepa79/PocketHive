package io.pockethive.journal.api;


/**
 * Responsibility: register scenario metadata and apply operator metadata updates.
 * Must not: authorize users or own swarm lifecycle.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public interface JournalRunMetadata {
    void register(String swarmId, String runId, String scenarioId);
    SwarmRunSummary update(String runId, SwarmRunMetadataUpdate update) throws Exception;
}
