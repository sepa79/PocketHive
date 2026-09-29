package io.pockethive.journal.api;


/**
 * Responsibility: apply effective bounds for configured journal retention.
 * Must not: read environment or change retention policy.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public record JournalRetentionSettings(int retentionDays, int createDaysBack, int createDaysAhead,
    int defaultMoveBatchSize, int defaultMaxFutureDays) {
    public JournalRetentionSettings {
        retentionDays = Math.max(1, retentionDays);
        createDaysBack = Math.max(0, createDaysBack);
        createDaysAhead = Math.max(0, createDaysAhead);
        defaultMoveBatchSize = Math.max(1, defaultMoveBatchSize);
        defaultMaxFutureDays = Math.max(1, defaultMaxFutureDays);
    }
}
