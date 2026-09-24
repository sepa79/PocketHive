package io.pockethive.journal.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalCursor;
import io.pockethive.journal.api.JournalEventQueries;
import io.pockethive.journal.api.JournalPageResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Responsibility: read live/archived SQL journal events and assemble their stable cursor pages.
 * Must not: authorize callers, choose active registry runs or write/retain journal data.
 * Contract: RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
public final class PostgresJournalEventQueries implements JournalEventQueries {
    private static final String EVENT_COLUMNS = """
        SELECT id, ts, swarm_id, run_id, scope_role, scope_instance,
          severity, direction, kind, type, origin, correlation_id, idempotency_key,
          routing_key, data::text AS data, raw::text AS raw, extra::text AS extra
        """;
    private static final String HIVE_EVENTS = "FROM journal_event WHERE scope = 'HIVE'";
    private static final String SWARM_EVENTS = "FROM journal_event WHERE scope = 'SWARM'";
    private static final String ARCHIVED_SWARM_EVENTS =
        "FROM journal_event_archive WHERE scope = 'SWARM' AND capture_id = ?";
    private final JdbcTemplate jdbc;
    private final JournalEventRowMapper rows;

    public PostgresJournalEventQueries(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rows = new JournalEventRowMapper(json);
    }

    @Override
    public JournalPageResponse hivePage(String swarmId, String runId, String correlationId,
        Instant beforeTs, Long beforeId, int limit) {
        return page(read(HIVE_EVENTS, new ArrayList<>(), swarmId, runId, correlationId, null,
            beforeTs, beforeId, limit, true), limit);
    }

    @Override
    public JournalPageResponse swarmPage(String swarmId, String runId, String correlationId,
        String severity, Instant beforeTs, Long beforeId, int limit) {
        return page(read(SWARM_EVENTS, new ArrayList<>(), swarmId, runId, correlationId, severity,
            beforeTs, beforeId, limit, true), limit);
    }

    @Override
    public JournalPageResponse archivedSwarmPage(UUID captureId, String swarmId, String runId,
        String correlationId, String severity, Instant beforeTs, Long beforeId, int limit) {
        return page(read(ARCHIVED_SWARM_EVENTS, new ArrayList<>(List.of(captureId)), swarmId, runId,
            correlationId, severity, beforeTs, beforeId, limit, true), limit);
    }

    @Override
    public List<Map<String, Object>> swarmTimeline(String swarmId, String runId, String severity) {
        return read(SWARM_EVENTS, new ArrayList<>(), swarmId, runId, null, severity, null, null, 0, false)
            .stream().map(JournalEventRow::entry).toList();
    }

    @Override
    public List<Map<String, Object>> archivedSwarmTimeline(UUID captureId, String swarmId, String runId,
        String severity) {
        return read(ARCHIVED_SWARM_EVENTS, new ArrayList<>(List.of(captureId)), swarmId, runId,
            null, severity, null, null, 0, false).stream().map(JournalEventRow::entry).toList();
    }

    private List<JournalEventRow> read(String sourceSql, List<Object> args, String swarmId, String runId,
        String correlationId, String severity, Instant beforeTs, Long beforeId, int limit, boolean paged) {
        StringBuilder sql = new StringBuilder(EVENT_COLUMNS).append(sourceSql);
        if (swarmId != null) {
            sql.append(" AND swarm_id = ?");
            args.add(swarmId);
        }
        if (runId != null) {
            sql.append(" AND run_id = ?");
            args.add(runId);
        }
        if (correlationId != null) {
            sql.append(" AND correlation_id = ?");
            args.add(correlationId);
        }
        if (severity != null) {
            sql.append(" AND severity = ?");
            args.add(severity);
        }
        if (beforeTs != null && beforeId != null) {
            sql.append(" AND (ts, id) < (?, ?)");
            args.add(Timestamp.from(beforeTs));
            args.add(beforeId);
        }
        if (paged) {
            sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
            args.add(limit + 1);
        } else {
            sql.append(" ORDER BY ts ASC, id ASC");
        }
        return jdbc.query(sql.toString(), args.toArray(), (rs, rowNum) -> rows.map(rs, paged));
    }

    private static JournalPageResponse page(List<JournalEventRow> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        JournalCursor cursor = null;
        if (hasMore && !rows.isEmpty()) {
            JournalEventRow last = rows.getLast();
            cursor = new JournalCursor(last.timestamp(), last.id());
        }
        return new JournalPageResponse(rows.stream().map(JournalEventRow::entry).toList(), cursor, hasMore);
    }

    @Override
    public String latestSwarmRun(String swarmId) {
        try {
            String sql = """
                SELECT run_id
                FROM journal_event
                WHERE scope = 'SWARM' AND swarm_id = ?
                ORDER BY ts DESC, id DESC
                LIMIT 1
                """;
            String latest = jdbc.query(sql, ps -> ps.setString(1, swarmId), rs -> rs.next() ? rs.getString(1) : null);
            if (latest != null && !latest.isBlank()) {
                return latest;
            }
            String pinned = jdbc.query(
                "SELECT run_id FROM journal_capture WHERE scope='SWARM' AND swarm_id=? AND pinned=true ORDER BY last_ts DESC NULLS LAST LIMIT 1",
                ps -> ps.setString(1, swarmId),
                rs -> rs.next() ? rs.getString(1) : null);
            if (pinned != null && !pinned.isBlank()) {
                return pinned;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public UUID pinnedSwarmCapture(String swarmId, String runId) {
        try {
            return jdbc.query(
                "SELECT id FROM journal_capture WHERE scope='SWARM' AND swarm_id=? AND run_id=? AND pinned=true LIMIT 1",
                ps -> {
                    ps.setString(1, swarmId);
                    ps.setString(2, runId);
                },
                rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null);
        } catch (Exception e) {
            return null;
        }
    }

}
