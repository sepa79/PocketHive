package io.pockethive.orchestrator.infra;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.*;
import io.pockethive.journal.postgres.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresJournalRetentionTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM journal_event");
        jdbc.update("DELETE FROM journal_capture");
        jdbc.update("DELETE FROM journal_run");
    }

    @Test
    void prunesOnlyExpiredPartitionsAndOutOfWindowDefaultRowsWhileKeepingArchives() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var retention = new PostgresJournalRetention(jdbc, new JournalRetentionSettings(2, 0, 0, 10, 3));
        retention.reconcile();
        String expired = partition(today.minusDays(3));
        String boundary = partition(today.minusDays(2));
        event(today.minusDays(3));
        event(today.minusDays(2));
        event(today.minusDays(4));
        event(today.minusDays(1));
        event(today);
        event(today.plusDays(2));
        event(today.plusDays(3));
        var capture = new PostgresJournalCaptures(jdbc).pin("alpha", "run", new PinRunRequest(null, "FULL", null));
        assertThat(capture.entries()).isEqualTo(7);
        retention.reconcile();
        assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, expired)).isNull();
        assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, boundary)).isEqualTo(boundary);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_event", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_event_default", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_event_archive", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT entries FROM journal_capture", Long.class)).isEqualTo(7);
    }

    private String partition(LocalDate day) {
        String name = "journal_event_" + day.format(DateTimeFormatter.BASIC_ISO_DATE);
        jdbc.execute("CREATE TABLE " + name + " PARTITION OF journal_event FOR VALUES FROM ('"
            + day + " 00:00:00+00') TO ('" + day.plusDays(1) + " 00:00:00+00')");
        return name;
    }

    private void event(LocalDate day) {
        jdbc.update("""
            INSERT INTO journal_event (ts, scope, swarm_id, run_id, scope_role, scope_instance,
                severity, direction, kind, type, origin)
            VALUES (?, 'SWARM', 'alpha', 'run', 'processor', 'worker', 'INFO', 'IN', 'event', 'test', 'test')
            """, Timestamp.from(day.atStartOfDay().toInstant(ZoneOffset.UTC)));
    }
}
