package io.pockethive.orchestrator.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalRunSummary;
import io.pockethive.journal.api.SwarmRunSummary;
import io.pockethive.journal.postgres.PostgresJournalRunQueries;
import java.sql.Timestamp;
import java.time.Instant;
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
class PostgresJournalRunQueriesTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant TIME = Instant.parse("2026-09-23T12:00:00Z");
    private static JdbcTemplate jdbc;
    private static PostgresJournalRunQueries runs;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        runs = new PostgresJournalRunQueries(jdbc, new ObjectMapper());
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM journal_event");
        jdbc.update("DELETE FROM journal_capture");
        jdbc.update("DELETE FROM journal_run");
    }

    @Test
    void mergesPinnedAndLiveWithoutMixingSwarmsSharingRunId() {
        capture("alpha", "shared", TIME.minusSeconds(10), TIME, 20);
        event("alpha", "shared", TIME.plusSeconds(10));
        event("beta", "shared", TIME.plusSeconds(20));
        metadata("alpha", "shared");
        var all = runs.allSwarmRuns(10, false, null);
        assertThat(all).extracting(SwarmRunSummary::swarmId).containsExactly("beta", "alpha");
        var alpha = all.get(1);
        assertThat(alpha.firstTs()).isEqualTo(TIME.minusSeconds(10));
        assertThat(alpha.lastTs()).isEqualTo(TIME.plusSeconds(10));
        assertThat(alpha.entries()).isEqualTo(20);
        assertThat(alpha.pinned()).isTrue();
        assertThat(alpha.tags()).containsExactly("tag");
        assertThat(runs.swarmRuns("alpha")).containsExactly(JournalRunSummary.from(alpha));
        assertThat(runs.swarmRuns("absent")).isEmpty();
    }

    @Test
    void afterTimestampFiltersLiveRowsBeforeAggregationButNeverPinnedSummaries() {
        capture("alpha", "archive", TIME.minusSeconds(20), TIME.minusSeconds(10), 7);
        event("alpha", "live", TIME.minusSeconds(5));
        event("alpha", "live", TIME.plusSeconds(5));
        var all = runs.allSwarmRuns(10, false, TIME);
        assertThat(all).extracting(SwarmRunSummary::runId).containsExactly("live", "archive");
        assertThat(all.getFirst().entries()).isEqualTo(1);
        assertThat(all.getFirst().firstTs()).isEqualTo(TIME.plusSeconds(5));
        assertThat(runs.allSwarmRuns(10, true, TIME.plusSeconds(100)))
            .extracting(SwarmRunSummary::runId).containsExactly("archive");
        assertThat(runs.allSwarmRuns(1, false, TIME)).extracting(SwarmRunSummary::runId).containsExactly("live");
    }

    @Test
    void preservesDifferentNullTimestampOrderForPinnedOnlyAndMergedLists() {
        capture("alpha", "empty", null, null, 0);
        capture("alpha", "dated", TIME, TIME, 1);
        assertThat(runs.allSwarmRuns(2, true, null)).extracting(SwarmRunSummary::runId)
            .containsExactly("dated", "empty");
        assertThat(runs.allSwarmRuns(2, false, null)).extracting(SwarmRunSummary::runId)
            .containsExactly("empty", "dated");
        assertThat(runs.swarmRuns("alpha")).extracting(JournalRunSummary::runId)
            .containsExactly("empty", "dated");
    }

    @Test
    void metadataSummarySupportsRunsWithoutLiveEventsAndKeepsWireFields() {
        metadata("alpha", "empty");
        capture("alpha", "empty", TIME, TIME, 9);
        var summary = runs.metadataSummary("alpha", "empty");
        assertThat(summary.entries()).isZero();
        assertThat(summary.firstTs()).isNull();
        assertThat(summary.lastTs()).isNull();
        assertThat(summary.pinned()).isFalse();
        assertThat(summary.scenarioId()).isEqualTo("scenario");
        assertThat(summary.tags()).containsExactly("tag");
        var wire = new ObjectMapper().valueToTree(summary);
        assertThat(wire.size()).isEqualTo(10);
        assertThat(wire.has("swarmId") && wire.has("runId") && wire.has("firstTs")
            && wire.has("lastTs") && wire.has("entries") && wire.has("pinned")
            && wire.has("scenarioId") && wire.has("testPlan") && wire.has("tags")
            && wire.has("description")).isTrue();
    }

    private void event(String swarm, String run, Instant ts) {
        jdbc.update("""
            INSERT INTO journal_event (ts, scope, swarm_id, run_id, scope_role, scope_instance,
                severity, direction, kind, type, origin)
            VALUES (?, 'SWARM', ?, ?, 'processor', 'worker-1', 'INFO', 'IN', 'signal', 'test', 'test')
            """, Timestamp.from(ts), swarm, run);
    }

    private void capture(String swarm, String run, Instant first, Instant last, long entries) {
        jdbc.update("""
            INSERT INTO journal_capture (id, scope, swarm_id, run_id, mode, pinned, first_ts, last_ts, entries)
            VALUES (?, 'SWARM', ?, ?, 'FULL', true, ?, ?, ?)
            """, UUID.randomUUID(), swarm, run, first == null ? null : Timestamp.from(first),
            last == null ? null : Timestamp.from(last), entries);
    }

    private void metadata(String swarm, String run) {
        jdbc.update("""
            INSERT INTO journal_run (swarm_id, run_id, scenario_id, tags)
            VALUES (?, ?, 'scenario', '[" tag ","tag"]'::jsonb)
            """, swarm, run);
    }
}
