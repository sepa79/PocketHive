package io.pockethive.orchestrator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalCaptures;
import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.JournalRetention;
import io.pockethive.journal.api.JournalRetentionSettings;
import io.pockethive.journal.postgres.PostgresJournalCaptures;
import io.pockethive.journal.postgres.PostgresJournalRunMetadata;
import io.pockethive.journal.postgres.PostgresJournalRetention;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Responsibility: compose journal write adapters and bind existing retention settings.
 * Must not: execute writes, normalize metadata or decide capture outcomes.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
@Configuration
public class JournalWriteConfiguration {
    @Bean
    JournalRunMetadata journalRunMetadata(JdbcTemplate jdbc, ObjectMapper json, JournalRunQueries runs) {
        return new PostgresJournalRunMetadata(jdbc, json, runs);
    }

    @Bean
    JournalCaptures journalCaptures(JdbcTemplate jdbc) {
        return new PostgresJournalCaptures(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "pockethive.journal.sink", havingValue = "postgres")
    JournalRetention journalRetention(JdbcTemplate jdbc,
        @Value("${pockethive.journal.postgres.retention-days:14}") int retentionDays,
        @Value("${pockethive.journal.postgres.partition.create-days-back:1}") int createDaysBack,
        @Value("${pockethive.journal.postgres.partition.create-days-ahead:2}") int createDaysAhead,
        @Value("${pockethive.journal.postgres.partition.default-move-batch-size:5000}") int defaultMoveBatchSize,
        @Value("${pockethive.journal.postgres.partition.default-max-future-days:14}") int defaultMaxFutureDays) {
        return new PostgresJournalRetention(jdbc, new JournalRetentionSettings(retentionDays,
            createDaysBack, createDaysAhead, defaultMoveBatchSize, defaultMaxFutureDays));
    }
}
