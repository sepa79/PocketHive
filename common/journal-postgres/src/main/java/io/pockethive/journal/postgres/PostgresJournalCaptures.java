package io.pockethive.journal.postgres;

import io.pockethive.journal.api.JournalCaptures;
import io.pockethive.journal.api.JournalCaptureConflictException;
import io.pockethive.journal.api.PinRunRequest;
import io.pockethive.journal.api.PinRunResponse;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Responsibility: create and populate pinned archives and report their persisted statistics.
 * Must not: authorize callers, select runs or delete retained data.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public final class PostgresJournalCaptures implements JournalCaptures {
    private final JdbcTemplate jdbc;

    public PostgresJournalCaptures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PinRunResponse pin(String cleanedId, String runId, PinRunRequest request) {
        PinMode mode = PinMode.fromNullable(request != null ? request.mode() : null);
        String existingMode = jdbc.query(
            "SELECT mode FROM journal_capture WHERE scope='SWARM' AND swarm_id=? AND run_id=?",
            ps -> {
                ps.setString(1, cleanedId);
                ps.setString(2, runId);
            },
            rs -> rs.next() ? rs.getString(1) : null);
        if (existingMode != null && !existingMode.equalsIgnoreCase(mode.name())) {
            throw new JournalCaptureConflictException(
                new PinRunResponse(null, cleanedId, runId, existingMode.toUpperCase(), 0, 0));
        }

        UUID captureId = jdbc.query(
            "SELECT id FROM journal_capture WHERE scope='SWARM' AND swarm_id=? AND run_id=?",
            ps -> {
                ps.setString(1, cleanedId);
                ps.setString(2, runId);
            },
            rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null);

        if (captureId == null) {
            captureId = UUID.randomUUID();
            jdbc.update(
                """
                    INSERT INTO journal_capture (
                      id,
                      scope,
                      swarm_id,
                      run_id,
                      mode,
                      pinned,
                      name
                    ) VALUES (
                      ?,
                      'SWARM',
                      ?,
                      ?,
                      ?,
                      true,
                      ?
                    )
                    ON CONFLICT (scope, swarm_id, run_id) DO NOTHING
                    """,
                captureId,
                cleanedId,
                runId,
                mode.name(),
                request != null ? request.name() : null);
            captureId = jdbc.query(
                "SELECT id FROM journal_capture WHERE scope='SWARM' AND swarm_id=? AND run_id=?",
                ps -> {
                    ps.setString(1, cleanedId);
                    ps.setString(2, runId);
                },
                rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null);
        }

        if (captureId == null) {
            throw new IllegalStateException("Journal capture was not created");
        }

        long inserted = copyIntoArchive(captureId, cleanedId, runId, mode);
        long entries = refreshCaptureStats(captureId);
        return new PinRunResponse(captureId.toString(), cleanedId, runId, mode.name(), inserted, entries);
    }

    private long copyIntoArchive(UUID captureId, String swarmId, String runId, PinMode mode) {
        String filter = "";
        if (mode == PinMode.ERRORS_ONLY) {
            filter = " AND severity IN ('WARN','ERROR')";
        }
        String rawExpr = mode == PinMode.SLIM ? "NULL::jsonb" : "raw";
        String extraExpr = mode == PinMode.SLIM ? "NULL::jsonb" : "extra";
        String sql = """
            INSERT INTO journal_event_archive (
              capture_id,
              source_id,
              ts,
              scope,
              swarm_id,
              run_id,
              scope_role,
              scope_instance,
              severity,
              direction,
              kind,
              type,
              origin,
              correlation_id,
              idempotency_key,
              routing_key,
              data,
              raw,
              extra
            )
            SELECT
              ?,
              id,
              ts,
              scope,
              swarm_id,
              run_id,
              scope_role,
              scope_instance,
              severity,
              direction,
              kind,
              type,
              origin,
              correlation_id,
              idempotency_key,
              routing_key,
              data,
              %s,
              %s
            FROM journal_event
            WHERE scope = 'SWARM' AND swarm_id = ? AND run_id = ?
            %s
            ON CONFLICT (capture_id, source_id) DO NOTHING
            """.formatted(rawExpr, extraExpr, filter);
        return jdbc.update(sql, ps -> {
            ps.setObject(1, captureId);
            ps.setString(2, swarmId);
            ps.setString(3, runId);
        });
    }

    private long refreshCaptureStats(UUID captureId) {
        Stats stats = jdbc.query(
            "SELECT MIN(ts) AS first_ts, MAX(ts) AS last_ts, COUNT(*) AS entries FROM journal_event_archive WHERE capture_id=?",
            ps -> ps.setObject(1, captureId),
            rs -> rs.next() ? new Stats(rs.getTimestamp("first_ts"), rs.getTimestamp("last_ts"), rs.getLong("entries")) : null);
        if (stats == null) {
            return 0;
        }
        jdbc.update(
            "UPDATE journal_capture SET first_ts=?, last_ts=?, entries=? WHERE id=?",
            stats.first,
            stats.last,
            stats.entries,
            captureId);
        return stats.entries;
    }

    private record Stats(java.sql.Timestamp first, java.sql.Timestamp last, long entries) {}
}
