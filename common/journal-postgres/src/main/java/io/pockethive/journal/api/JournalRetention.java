package io.pockethive.journal.api;


/**
 * Responsibility: maintain journal partitions and apply configured retention.
 * Must not: manage swarm lifecycle or delete pinned archives.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public interface JournalRetention {
    void reconcile();
}
