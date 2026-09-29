package io.pockethive.journal.api;


/**
 * Responsibility: pin a resolved swarm run and report archive counts or mode conflict.
 * Must not: select runs or authorize callers.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public interface JournalCaptures {
    PinRunResponse pin(String swarmId, String runId, PinRunRequest request);
}
