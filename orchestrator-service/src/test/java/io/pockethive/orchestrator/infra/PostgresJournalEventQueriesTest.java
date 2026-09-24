package io.pockethive.orchestrator.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.journal.api.JournalEventQueries;
import io.pockethive.journal.postgres.PostgresJournalEventQueries;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
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
class PostgresJournalEventQueriesTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant TIME = Instant.parse("2026-09-23T12:00:00Z");
    private static JdbcTemplate jdbc;
    private static JournalEventQueries events;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        events = new PostgresJournalEventQueries(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM journal_event");
        jdbc.update("DELETE FROM journal_capture");
    }

    @Test
    void hivePagesUseIdTieBreakerAndKeepOptionalFiltersAndWireShape() {
        long first = insert("HIVE", "alpha", "run-1", "INFO", "first", "corr");
        long second = insert("HIVE", "alpha", "run-1", "INFO", "second", "corr");
        long third = insert("HIVE", "alpha", "run-1", "INFO", "third", "corr");
        insert("HIVE", "alpha", "run-2", "INFO", "different-run", "corr");
        insert("HIVE", "beta", "run-1", "INFO", "different-swarm", "corr");
        insert("SWARM", "alpha", "run-1", "INFO", "different-scope", "corr");
        insert("HIVE", "alpha", "run-1", "INFO", "different-correlation", "other");
        var page = events.hivePage("alpha", "run-1", "corr", null, null, 2);
        assertThat(page.items()).extracting(e -> e.get("eventId")).containsExactly(third, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor().id()).isEqualTo(second);
        assertThat(page.nextCursor().ts()).isEqualTo(TIME);
        var next = events.hivePage("alpha", "run-1", "corr", page.nextCursor().ts(), page.nextCursor().id(), 2);
        assertThat(next.items()).extracting(e -> e.get("eventId")).containsExactly(first);
        assertThat(next.hasMore()).isFalse();
        assertThat(next.nextCursor()).isNull();
        assertThat(events.hivePage(null, null, null, null, null, 100).items()).hasSize(6);
        var wire = new ObjectMapper().findAndRegisterModules().valueToTree(page);
        assertThat(wire.size()).isEqualTo(3);
        assertThat(wire.has("items") && wire.has("hasMore") && wire.has("nextCursor")).isTrue();
        assertThat(wire.get("nextCursor").size()).isEqualTo(2);
        assertThat(wire.get("nextCursor").has("ts") && wire.get("nextCursor").has("id")).isTrue();
    }

    @Test
    void swarmTimelineAndPageSharePayloadMappingButKeepOrderAndEventIdDifference() {
        insert("SWARM", "alpha", "run-1", "INFO", "first", "corr");
        insert("SWARM", "alpha", "run-1", "ERROR", "second", "corr");
        insert("SWARM", "alpha", "run-2", "ERROR", "other-run", "corr");
        insert("SWARM", "beta", "run-1", "ERROR", "other-swarm", "corr");
        var timeline = events.swarmTimeline("alpha", "run-1", null);
        assertThat(timeline).extracting(e -> e.get("type")).containsExactly("first", "second");
        assertThat(timeline.getFirst()).doesNotContainKey("eventId")
            .containsEntry("scope", new ControlScope("alpha", "processor", "worker-1"))
            .containsEntry("data", Map.of("ok", true)).containsEntry("raw", null)
            .containsEntry("correlationId", "corr").containsEntry("idempotencyKey", "idem")
            .containsEntry("routingKey", "route");
        assertThat(events.swarmTimeline("alpha", "run-1", "ERROR"))
            .extracting(e -> e.get("type")).containsExactly("second");
        assertThat(events.swarmPage("alpha", "run-1", "corr", null, null, null, 10).items())
            .extracting(e -> e.get("type")).containsExactly("second", "first");
        assertThat(events.swarmPage("alpha", "run-1", "other", null, null, null, 10).items()).isEmpty();
        assertThat(events.swarmPage("alpha", "run-1", null, "ERROR", null, null, 10).items())
            .extracting(e -> e.get("type")).containsExactly("second");
    }

    @Test
    void archivedPagesUseArchiveIdsAndCannotLeakOtherCapturesOrLiveEntries() {
        insert("SWARM", "alpha", "run-1", "ERROR", "first", "corr");
        insert("SWARM", "alpha", "run-1", "ERROR", "second", "corr");
        UUID capture = capture("alpha", "run-1", true);
        archive(capture, "alpha", "run-1");
        insert("SWARM", "alpha", "run-1", "ERROR", "live-only", "corr");
        var page = events.archivedSwarmPage(capture, "alpha", "run-1", "corr", "ERROR", null, null, 1);
        assertThat(page.items()).extracting(e -> e.get("type")).containsExactly("second");
        var next = events.archivedSwarmPage(capture, "alpha", "run-1", "corr", "ERROR",
            page.nextCursor().ts(), page.nextCursor().id(), 1);
        assertThat(next.items()).extracting(e -> e.get("type")).containsExactly("first");
        assertThat(next.hasMore()).isFalse();
        assertThat(next.nextCursor()).isNull();
        assertThat(events.archivedSwarmTimeline(capture, "alpha", "run-1", "ERROR"))
            .extracting(e -> e.get("type")).containsExactly("first", "second");
        assertThat(events.archivedSwarmTimeline(UUID.randomUUID(), "alpha", "run-1", null)).isEmpty();
        assertThat(events.archivedSwarmTimeline(capture, "beta", "run-1", null)).isEmpty();
        assertThat(events.archivedSwarmTimeline(capture, "alpha", "run-2", null)).isEmpty();
    }

    @Test
    void storedRunLookupPreservesLivePrecedenceAndUsesOnlyPinnedArchivesWhenLiveIsAbsent() {
        UUID capture = capture("alpha", "archived-run", true);
        assertThat(events.latestSwarmRun("alpha")).isEqualTo("archived-run");
        insert("SWARM", "alpha", "live-run", "INFO", "event", "corr");
        assertThat(events.latestSwarmRun("alpha")).isEqualTo("live-run");
        assertThat(events.pinnedSwarmCapture("alpha", "archived-run")).isEqualTo(capture);
        assertThat(events.pinnedSwarmCapture("alpha", "live-run")).isNull();
        capture("beta", "not-pinned", false);
        assertThat(events.latestSwarmRun("beta")).isNull();
        assertThat(events.pinnedSwarmCapture("beta", "not-pinned")).isNull();
        assertThat(events.latestSwarmRun("missing")).isNull();
    }

    private long insert(String scope, String swarm, String run, String severity, String type, String correlation) {
        return jdbc.queryForObject("""
            INSERT INTO journal_event (ts, scope, swarm_id, run_id, scope_role, scope_instance,
                severity, direction, kind, type, origin, correlation_id, idempotency_key, routing_key, data)
            VALUES (?, ?, ?, ?, 'processor', 'worker-1', ?, 'IN', 'signal', ?, 'test', ?, 'idem', 'route', '{"ok":true}'::jsonb)
            RETURNING id
            """, Long.class, Timestamp.from(TIME), scope, swarm, run, severity, type, correlation);
    }

    private UUID capture(String swarm, String run, boolean pinned) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_capture (id, scope, swarm_id, run_id, mode, pinned) VALUES (?, 'SWARM', ?, ?, 'FULL', ?)",
            id, swarm, run, pinned);
        return id;
    }

    private void archive(UUID capture, String swarm, String run) {
        jdbc.update("""
            INSERT INTO journal_event_archive (capture_id, source_id, ts, scope, swarm_id, run_id,
                scope_role, scope_instance, severity, direction, kind, type, origin, correlation_id, idempotency_key, routing_key, data)
            SELECT ?, id, ts, scope, swarm_id, run_id, scope_role, scope_instance,
                severity, direction, kind, type, origin, correlation_id, idempotency_key, routing_key, data
            FROM journal_event WHERE scope='SWARM' AND swarm_id=? AND run_id=? ORDER BY id
            """, capture, swarm, run);
    }
}
