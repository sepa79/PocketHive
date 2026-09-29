package io.pockethive.journal.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.JournalRunSummary;
import io.pockethive.journal.api.SwarmRunSummary;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Responsibility: read run listings and metadata summaries with the existing query/filter order.
 * Must not: authorize users, access registry state or write metadata/captures.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries.
 */
public final class PostgresJournalRunQueries implements JournalRunQueries {
    private final JdbcTemplate jdbc;
    private final JournalRunRowMapper rows;

    public PostgresJournalRunQueries(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rows = new JournalRunRowMapper(json);
    }

    @Override
    public List<JournalRunSummary> swarmRuns(String swarmId) {
        String mainSql = """
            SELECT
              run_id,
              MIN(ts) AS first_ts,
              MAX(ts) AS last_ts,
              COUNT(*) AS entries
            FROM journal_event
            WHERE scope = 'SWARM' AND swarm_id = ?
            GROUP BY run_id
            ORDER BY last_ts DESC
            """;
        List<SwarmRunSummary> live = jdbc.query(mainSql, ps -> ps.setString(1, swarmId),
            (rs, rowNum) -> rows.forSwarm(rs, swarmId, false));
        String captureSql = """
            SELECT run_id, first_ts, last_ts, entries
            FROM journal_capture
            WHERE scope = 'SWARM' AND swarm_id = ? AND pinned = true
            ORDER BY last_ts DESC NULLS LAST
            """;
        List<SwarmRunSummary> pinned = jdbc.query(captureSql, ps -> ps.setString(1, swarmId),
            (rs, rowNum) -> rows.forSwarm(rs, swarmId, true));
        return JournalRunSummaries.merge(pinned, live).stream().map(JournalRunSummary::from).toList();
    }

    @Override
    public List<SwarmRunSummary> allSwarmRuns(int pageSize, boolean pinnedOnly, Instant afterTs) {
        String captureSql = """
          SELECT
            c.swarm_id,
            c.run_id,
            c.first_ts,
            c.last_ts,
            c.entries,
            r.scenario_id,
            r.test_plan,
            r.description,
            r.tags::text AS tags
          FROM journal_capture c
          LEFT JOIN journal_run r
            ON r.swarm_id = c.swarm_id AND r.run_id = c.run_id
          WHERE c.scope = 'SWARM' AND c.pinned = true
          ORDER BY c.last_ts DESC NULLS LAST
          """;
        List<SwarmRunSummary> pinnedRuns = jdbc.query(captureSql, (rs, rowNum) -> rows.full(rs, true));
        if (pinnedOnly) {
            return pinnedRuns.stream().filter(run -> run.runId() != null && run.swarmId() != null)
                .limit(pageSize).toList();
        }
        String mainSql = afterTs != null ? """
          SELECT
            e.swarm_id,
            e.run_id,
            MIN(e.ts) AS first_ts,
            MAX(e.ts) AS last_ts,
            COUNT(*) AS entries,
            r.scenario_id,
            r.test_plan,
            r.description,
            r.tags::text AS tags
          FROM journal_event e
          LEFT JOIN journal_run r
            ON r.swarm_id = e.swarm_id AND r.run_id = e.run_id
          WHERE e.scope = 'SWARM' AND e.ts >= ?
          GROUP BY e.swarm_id, e.run_id, r.scenario_id, r.test_plan, r.description, r.tags
          ORDER BY last_ts DESC
          LIMIT ?
          """ : """
          SELECT
            e.swarm_id,
            e.run_id,
            MIN(e.ts) AS first_ts,
            MAX(e.ts) AS last_ts,
            COUNT(*) AS entries,
            r.scenario_id,
            r.test_plan,
            r.description,
            r.tags::text AS tags
          FROM journal_event e
          LEFT JOIN journal_run r
            ON r.swarm_id = e.swarm_id AND r.run_id = e.run_id
          WHERE e.scope = 'SWARM'
          GROUP BY e.swarm_id, e.run_id, r.scenario_id, r.test_plan, r.description, r.tags
          ORDER BY last_ts DESC
          LIMIT ?
          """;
        List<SwarmRunSummary> mainRuns = jdbc.query(mainSql, ps -> {
            if (afterTs != null) {
                ps.setTimestamp(1, java.sql.Timestamp.from(afterTs));
                ps.setInt(2, pageSize);
            } else {
                ps.setInt(1, pageSize);
            }
        }, (rs, rowNum) -> rows.full(rs, false));
        return JournalRunSummaries.merge(pinnedRuns, mainRuns).stream().limit(pageSize).toList();
    }

    @Override
    public SwarmRunSummary metadataSummary(String swarmId, String runId) {
        String summarySql = """
          SELECT
            COALESCE(e.swarm_id, r.swarm_id) AS swarm_id,
            COALESCE(e.run_id, r.run_id) AS run_id,
            MIN(e.ts) AS first_ts,
            MAX(e.ts) AS last_ts,
            COUNT(e.id) AS entries,
            false AS pinned,
            r.scenario_id,
            r.test_plan,
            r.description,
            r.tags::text AS tags
          FROM journal_run r
          LEFT JOIN journal_event e
            ON e.scope = 'SWARM' AND e.swarm_id = r.swarm_id AND e.run_id = r.run_id
          WHERE r.swarm_id = ? AND r.run_id = ?
          GROUP BY COALESCE(e.swarm_id, r.swarm_id), COALESCE(e.run_id, r.run_id), r.scenario_id, r.test_plan, r.description, r.tags
          """;
        return jdbc.queryForObject(summarySql, (rs, rowNum) -> rows.full(rs, false), swarmId, runId);
    }
}
