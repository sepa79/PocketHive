package io.pockethive.orchestrator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalEventQueries;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.postgres.PostgresJournalRunQueries;
import io.pockethive.journal.postgres.PostgresJournalEventQueries;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Responsibility: compose journal event and run queries with the configured JDBC and JSON dependencies.
 * Must not: query events or define run-selection and HTTP behavior.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries;
 * RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
@Configuration
public class JournalEventQueryConfiguration {
    @Bean
    JournalEventQueries journalEventQueries(JdbcTemplate jdbc, ObjectMapper json) {
        return new PostgresJournalEventQueries(jdbc, json);
    }
    @Bean
    JournalRunQueries journalRunQueries(JdbcTemplate jdbc, ObjectMapper json) {
        return new PostgresJournalRunQueries(jdbc, json);
    }
}
