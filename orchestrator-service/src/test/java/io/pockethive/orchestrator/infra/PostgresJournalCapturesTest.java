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
class PostgresJournalCapturesTest {
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

    private final Instant time = Instant.parse("2026-09-23T10:00:00Z");

    @Test
    void fullCaptureIsIsolatedAndRepeatOnlyCopiesNewEventsAndRefreshesStats() {
        var captures = new PostgresJournalCaptures(jdbc);
        event("alpha", "run", "INFO", time);
        event("beta", "run", "ERROR", time);
        event("alpha", "other-run", "ERROR", time);
        var request = new PinRunRequest("run", "FULL", "first");
        var first = captures.pin("alpha", "run", request);
        assertThat(first.inserted()).isEqualTo(1);
        assertThat(first.entries()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT raw::text FROM journal_event_archive", String.class)).contains("payload");
        assertThat(jdbc.queryForObject("SELECT extra::text FROM journal_event_archive", String.class)).contains("context");
        var repeat = captures.pin("alpha", "run", request);
        assertThat(repeat.captureId()).isEqualTo(first.captureId());
        assertThat(repeat.inserted()).isZero();
        event("alpha", "run", "WARN", time.plusSeconds(10));
        var extended = captures.pin("alpha", "run", request);
        assertThat(extended.entries()).isEqualTo(2);
        assertThat(extended.inserted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_ts FROM journal_capture", Timestamp.class).toInstant())
            .isEqualTo(time.plusSeconds(10));
    }

    @Test
    void errorsOnlyIncludesWarnAndErrorWhileSlimOmitsRawAndExtra() {
        var captures = new PostgresJournalCaptures(jdbc);
        event("alpha", "run", "INFO", time);
        event("alpha", "run", "WARN", time.plusSeconds(1));
        event("alpha", "run", "ERROR", time.plusSeconds(2));
        var errors = captures.pin("alpha", "run", new PinRunRequest(null, "ERRORS_ONLY", null));
        assertThat(errors.entries()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT severity FROM journal_event_archive ORDER BY ts", String.class))
            .containsExactly("WARN", "ERROR");
        event("beta", "run", "INFO", time);
        var slim = captures.pin("beta", "run", null);
        assertThat(slim.mode()).isEqualTo("SLIM");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_event_archive WHERE swarm_id='beta' AND raw IS NULL AND extra IS NULL AND data IS NOT NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void modeConflictPreservesCaptureAndEmptyUnknownRunCanBePinned() {
        var captures = new PostgresJournalCaptures(jdbc);
        var first = captures.pin("alpha", "empty", new PinRunRequest(null, "FULL", "name"));
        assertThat(first.entries()).isZero();
        assertThatThrownBy(() -> captures.pin("alpha", "empty", null))
            .isInstanceOfSatisfying(JournalCaptureConflictException.class, conflict -> {
                assertThat(conflict.response()).isEqualTo(new PinRunResponse(null, "alpha", "empty", "FULL", 0, 0));
            });
        assertThat(jdbc.queryForObject("SELECT mode FROM journal_capture", String.class)).isEqualTo("FULL");
        assertThat(jdbc.queryForObject("SELECT name FROM journal_capture", String.class)).isEqualTo("name");
    }

    private void event(String swarm, String run, String severity, Instant ts) {
        jdbc.update("""
            INSERT INTO journal_event (ts, scope, swarm_id, run_id, scope_role, scope_instance,
                severity, direction, kind, type, origin, data, raw, extra)
            VALUES (?, 'SWARM', ?, ?, 'processor', 'worker', ?, 'IN', 'event', 'test', 'test',
                '{"value":1}'::jsonb, '{"payload":2}'::jsonb, '{"context":3}'::jsonb)
            """, Timestamp.from(ts), swarm, run, severity);
    }
}
