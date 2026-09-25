package io.pockethive.orchestrator.config;

import io.pockethive.journal.api.JournalRetention;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Responsibility: trigger the configured journal retention port on the existing schedule.
 * Must not: execute SQL, resolve retention cutoffs or construct partition names.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
@Component
@ConditionalOnProperty(name = "pockethive.journal.sink", havingValue = "postgres")
public class JournalRetentionSchedule {
    private final JournalRetention retention;

    public JournalRetentionSchedule(JournalRetention retention) {
        this.retention = retention;
    }

    @Scheduled(
        initialDelayString = "${pockethive.journal.postgres.partition.reconcile.initial-delay-ms:2000}",
        fixedDelayString = "${pockethive.journal.postgres.partition.reconcile.fixed-delay-ms:60000}")
    public void reconcile() {
        retention.reconcile();
    }
}
