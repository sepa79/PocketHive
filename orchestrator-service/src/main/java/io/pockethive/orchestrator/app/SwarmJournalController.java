package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/swarms")
public class SwarmJournalController {
    private static final Logger log = LoggerFactory.getLogger(SwarmJournalController.class);
    private static final String SCENARIOS_RUNTIME_ROOT = "scenarios-runtime";

    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final SwarmStore store;

    @Value("${pockethive.journal.sink:postgres}")
    private String journalSink;

    public SwarmJournalController(ObjectMapper json, JdbcTemplate jdbc, SwarmStore store) {
        this.json = json;
        this.jdbc = jdbc;
        this.store = store;
    }

    /**
     * GET {@code /api/swarms/{swarmId}/journal} — return swarm-level journal events when available.
     * <p>
     * This projects the per-swarm journal file into a simple JSON array so Hive UI can render
     * a debug timeline without introducing new control-plane contracts or touching the swarm-controller.
     */
    @GetMapping("/{swarmId}/journal")
    public ResponseEntity<List<Map<String, Object>>> journal(@PathVariable String swarmId,
                                                             @RequestParam(required = false) String runId) {
        String path = "/api/swarms/" + swarmId + "/journal";
        logRestRequest("GET", path, null);
        ResponseEntity<List<Map<String, Object>>> response;
        try {
            List<Map<String, Object>> entries = readJournalEntries(swarmId, runId);
            if (entries == null) {
                response = ResponseEntity.notFound().build();
            } else {
                response = ResponseEntity.ok(entries);
            }
        } catch (Exception ex) {
            log.warn("Failed to read journal for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("GET", path, response);
        return response;
    }

    /**
     * GET {@code /api/swarms/{swarmId}/journal/page} — paginated swarm-level journal query (Postgres only).
     * <p>
     * Results are returned newest-first (descending by {@code (ts, id)}). Use {@code nextCursor} as the
     * {@code beforeTs}/{@code beforeId} pair for the next page (older entries).
     */
    @GetMapping("/{swarmId}/journal/page")
    public ResponseEntity<JournalPageResponse> journalPage(@PathVariable String swarmId,
                                                           @RequestParam(required = false)
                                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                           Instant beforeTs,
                                                           @RequestParam(required = false) Long beforeId,
                                                           @RequestParam(required = false) Integer limit,
                                                           @RequestParam(required = false) String runId,
                                                           @RequestParam(required = false) String correlationId) {
        String path = "/api/swarms/" + swarmId + "/journal/page";
        logRestRequest("GET", path, null);
        if (!"postgres".equalsIgnoreCase(journalSink)) {
            ResponseEntity<JournalPageResponse> response = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
            logRestResponse("GET", path, response);
            return response;
        }
        if ((beforeTs == null) != (beforeId == null)) {
            ResponseEntity<JournalPageResponse> response = ResponseEntity.badRequest().build();
            logRestResponse("GET", path, response);
            return response;
        }
        int pageSize = limit == null ? 200 : Math.max(1, Math.min(1000, limit));
        String corr = correlationId == null ? null : correlationId.trim();
        if (corr != null && corr.isBlank()) {
            corr = null;
        }
        ResponseEntity<JournalPageResponse> response;
        try {
            JournalPageResponse page = readJournalPageFromPostgres("SWARM", swarmId, runId, corr, beforeTs, beforeId, pageSize);
            if (page == null) {
                response = ResponseEntity.notFound().build();
            } else {
                response = ResponseEntity.ok(page);
            }
        } catch (Exception ex) {
            log.warn("Failed to query journal page for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("GET", path, response);
        return response;
    }

    /**
     * GET {@code /api/swarms/{swarmId}/journal/runs} — list known journal runs for a swarm (Postgres only).
     * <p>
     * Runs are returned newest-first (by last entry time).
     */
    @GetMapping("/{swarmId}/journal/runs")
    public ResponseEntity<List<JournalRunSummary>> journalRuns(@PathVariable String swarmId) {
        String path = "/api/swarms/" + swarmId + "/journal/runs";
        logRestRequest("GET", path, null);
        if (!"postgres".equalsIgnoreCase(journalSink)) {
            ResponseEntity<List<JournalRunSummary>> response = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
            logRestResponse("GET", path, response);
            return response;
        }
        String cleanedId = sanitizeSegment(swarmId);
        if (cleanedId == null) {
            ResponseEntity<List<JournalRunSummary>> response = ResponseEntity.notFound().build();
            logRestResponse("GET", path, response);
            return response;
        }
        ResponseEntity<List<JournalRunSummary>> response;
        try {
            String sql = """
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
            List<JournalRunSummary> mainRuns = jdbc.query(sql, ps -> ps.setString(1, cleanedId), (rs, rowNum) -> {
                String runId = rs.getString("run_id");
                java.sql.Timestamp first = rs.getTimestamp("first_ts");
                java.sql.Timestamp last = rs.getTimestamp("last_ts");
                long entries = rs.getLong("entries");
                return new JournalRunSummary(
                    runId,
                    first == null ? null : first.toInstant(),
                    last == null ? null : last.toInstant(),
                    entries,
                    false);
            });

            String captureSql = """
                SELECT run_id, first_ts, last_ts, entries
                FROM journal_capture
                WHERE scope = 'SWARM' AND swarm_id = ? AND pinned = true
                ORDER BY last_ts DESC NULLS LAST
                """;
            List<JournalRunSummary> pinnedRuns = jdbc.query(captureSql, ps -> ps.setString(1, cleanedId), (rs, rowNum) -> {
                String runId = rs.getString("run_id");
                java.sql.Timestamp first = rs.getTimestamp("first_ts");
                java.sql.Timestamp last = rs.getTimestamp("last_ts");
                long entries = rs.getLong("entries");
                return new JournalRunSummary(
                    runId,
                    first == null ? null : first.toInstant(),
                    last == null ? null : last.toInstant(),
                    entries,
                    true);
            });

            Map<String, JournalRunSummary> merged = new java.util.LinkedHashMap<>();
            for (JournalRunSummary run : pinnedRuns) {
                if (run.runId() != null) {
                    merged.put(run.runId(), run);
                }
            }
            for (JournalRunSummary run : mainRuns) {
                if (run.runId() == null) {
                    continue;
                }
                JournalRunSummary existing = merged.get(run.runId());
                if (existing == null) {
                    merged.put(run.runId(), run);
                } else if (existing.lastTs() == null || (run.lastTs() != null && run.lastTs().isAfter(existing.lastTs()))) {
                    merged.put(run.runId(), new JournalRunSummary(
                        existing.runId(),
                        existing.firstTs() != null ? existing.firstTs() : run.firstTs(),
                        run.lastTs(),
                        Math.max(existing.entries(), run.entries()),
                        true));
                }
            }

            List<JournalRunSummary> runs = merged.values().stream()
                .sorted(Comparator.comparing(JournalRunSummary::lastTs, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();

            if (runs.isEmpty() && store.find(cleanedId).isEmpty()) {
                response = ResponseEntity.notFound().build();
            } else {
                response = ResponseEntity.ok(List.copyOf(runs));
            }
        } catch (Exception ex) {
            log.warn("Failed to list journal runs for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("GET", path, response);
        return response;
    }

    @PostMapping("/{swarmId}/journal/pin")
    public ResponseEntity<PinRunResponse> pinSwarmJournalRun(@PathVariable String swarmId,
                                                             @RequestBody(required = false) PinRunRequest request) {
        String path = "/api/swarms/" + swarmId + "/journal/pin";
        logRestRequest("POST", path, request);
        if (!"postgres".equalsIgnoreCase(journalSink)) {
            ResponseEntity<PinRunResponse> response = ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
            logRestResponse("POST", path, response);
            return response;
        }
        String cleanedId = sanitizeSegment(swarmId);
        if (cleanedId == null) {
            ResponseEntity<PinRunResponse> response = ResponseEntity.notFound().build();
            logRestResponse("POST", path, response);
            return response;
        }

        String requestedRunId = request != null ? request.runId() : null;
        String runId = resolveActiveRunId(cleanedId, requestedRunId);
        if (runId == null) {
            ResponseEntity<PinRunResponse> response = ResponseEntity.notFound().build();
            logRestResponse("POST", path, response);
            return response;
        }

        PinMode mode = PinMode.fromNullable(request != null ? request.mode() : null);

        ResponseEntity<PinRunResponse> response;
        try {
            String existingMode = jdbc.query(
                "SELECT mode FROM journal_capture WHERE scope='SWARM' AND swarm_id=? AND run_id=?",
                ps -> {
                    ps.setString(1, cleanedId);
                    ps.setString(2, runId);
                },
                rs -> rs.next() ? rs.getString(1) : null);
            if (existingMode != null && !existingMode.equalsIgnoreCase(mode.name())) {
                response = ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new PinRunResponse(null, cleanedId, runId, existingMode.toUpperCase(), 0, 0));
                logRestResponse("POST", path, response);
                return response;
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
                response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                logRestResponse("POST", path, response);
                return response;
            }

            long inserted = copyIntoArchive(captureId, cleanedId, runId, mode);
            long entries = refreshCaptureStats(captureId);
            response = ResponseEntity.ok(new PinRunResponse(captureId.toString(), cleanedId, runId, mode.name(), inserted, entries));
        } catch (Exception ex) {
            log.warn("Failed to pin journal run for swarm {}: {}", swarmId, ex.getMessage());
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        logRestResponse("POST", path, response);
        return response;
    }

    private List<Map<String, Object>> readJournalEntries(String swarmId, String requestedRunId) {
        if ("postgres".equalsIgnoreCase(journalSink)) {
            return readJournalEntriesFromPostgres(swarmId, requestedRunId);
        }
        Path root = Paths.get(SCENARIOS_RUNTIME_ROOT).toAbsolutePath().normalize();
        String cleanedId = sanitizeSegment(swarmId);
        if (cleanedId == null) {
            return null;
        }
        String runId = resolveRunIdFromRuntimeRoot(root, cleanedId, requestedRunId);
        if (runId == null) {
            return null;
        }
        Path dir = root.resolve(cleanedId).normalize();
        if (!dir.startsWith(root)) {
            return null;
        }
        Path journal = dir.resolve(runId).resolve("journal.ndjson");
        if (!Files.isRegularFile(journal)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(journal);
        } catch (Exception ex) {
            log.warn("Unable to read journal file {}: {}", journal, ex.getMessage());
            return null;
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = json.readValue(trimmed, Map.class);
                result.add(entry);
            } catch (Exception ex) {
                log.warn("Skipping malformed journal line in {}: {}", journal, ex.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private String resolveRunIdFromRuntimeRoot(Path runtimeRoot, String swarmId, String requestedRunId) {
        String candidate = requestedRunId == null ? null : requestedRunId.trim();
        if (candidate != null && candidate.isBlank()) {
            candidate = null;
        }
        if (candidate != null) {
            return candidate;
        }
        String active = store.find(swarmId)
            .map(Swarm::getRunId)
            .orElse(null);
        if (active != null && !active.isBlank()) {
            return active;
        }
        try {
            Path base = runtimeRoot.resolve(swarmId).normalize();
            if (!base.startsWith(runtimeRoot) || !Files.isDirectory(base)) {
                return null;
            }
            try (var stream = Files.list(base)) {
                return stream
                    .filter(Files::isDirectory)
                    .max(Comparator.comparing(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (Exception e) {
                            return 0L;
                        }
                    }))
                    .map(path -> path.getFileName().toString())
                    .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private JournalPageResponse readJournalPageFromPostgres(String scope,
                                                           String swarmId,
                                                           String requestedRunId,
                                                           String correlationId,
                                                           Instant beforeTs,
                                                           Long beforeId,
                                                           int limit) {
        String cleanedId = sanitizeSegment(swarmId);
        if (cleanedId == null) {
            return null;
        }
        String runId = resolveActiveRunId(cleanedId, requestedRunId);
        if (runId == null) {
            return null;
        }

        UUID pinnedCapture = "SWARM".equalsIgnoreCase(scope) ? findPinnedCaptureId(cleanedId, runId) : null;
        if (pinnedCapture != null) {
            return readJournalPageFromArchive(pinnedCapture, scope, cleanedId, runId, correlationId, beforeTs, beforeId, limit);
        }
        record Row(long id, Instant ts, Map<String, Object> entry) {}

        StringBuilder sql = new StringBuilder("""
            SELECT
              id,
              ts,
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
              data::text AS data,
              raw::text AS raw,
              extra::text AS extra
            FROM journal_event
            WHERE scope = ? AND swarm_id = ? AND run_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(scope);
        args.add(cleanedId);
        args.add(runId);
        if (correlationId != null) {
            sql.append(" AND correlation_id = ?");
            args.add(correlationId);
        }
        if (beforeTs != null && beforeId != null) {
            sql.append(" AND (ts, id) < (?, ?)");
            args.add(java.sql.Timestamp.from(beforeTs));
            args.add(beforeId);
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
        args.add(limit + 1);

        List<Row> rows = jdbc.query(sql.toString(), args.toArray(), (rs, rowNum) -> {
            long id = rs.getLong("id");
            var entry = new LinkedHashMap<String, Object>();
            var ts = rs.getTimestamp("ts");
            Instant instant = ts == null ? Instant.EPOCH : ts.toInstant();
            entry.put("eventId", id);
            entry.put("timestamp", instant);
            String resolvedSwarmId = rs.getString("swarm_id");
            entry.put("swarmId", resolvedSwarmId);
            entry.put("runId", rs.getString("run_id"));
            entry.put("severity", rs.getString("severity"));
            entry.put("direction", rs.getString("direction"));
            entry.put("kind", rs.getString("kind"));
            entry.put("type", rs.getString("type"));
            entry.put("origin", rs.getString("origin"));
            String role = rs.getString("scope_role");
            String instance = rs.getString("scope_instance");
            entry.put("scope", new ControlScope(resolvedSwarmId, role, instance));
            entry.put("correlationId", rs.getString("correlation_id"));
            entry.put("idempotencyKey", rs.getString("idempotency_key"));
            entry.put("routingKey", rs.getString("routing_key"));
            entry.put("data", parseJsonMap(rs.getString("data")));
            entry.put("raw", parseJsonMap(rs.getString("raw")));
            entry.put("extra", parseJsonMap(rs.getString("extra")));
            return new Row(id, instant, java.util.Collections.unmodifiableMap(entry));
        });

        if (rows.isEmpty() && store.find(cleanedId).isEmpty()) {
            return null;
        }

        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        JournalPageResponse.Cursor cursor = null;
        if (hasMore && !rows.isEmpty()) {
            Row last = rows.get(rows.size() - 1);
            cursor = new JournalPageResponse.Cursor(last.ts(), last.id());
        }
        List<Map<String, Object>> items = rows.stream().map(Row::entry).toList();
        return new JournalPageResponse(items, cursor, hasMore);
    }

    private List<Map<String, Object>> readJournalEntriesFromPostgres(String swarmId, String requestedRunId) {
        String cleanedId = sanitizeSegment(swarmId);
        if (cleanedId == null) {
            return null;
        }
        String runId = resolveActiveRunId(cleanedId, requestedRunId);
        if (runId == null) {
            return null;
        }
        UUID pinnedCapture = findPinnedCaptureId(cleanedId, runId);
        if (pinnedCapture != null) {
            return readJournalEntriesFromArchive(pinnedCapture, cleanedId, runId);
        }
        String sql = """
            SELECT
              ts,
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
              data::text AS data,
              raw::text AS raw,
              extra::text AS extra
            FROM journal_event
            WHERE scope = 'SWARM' AND swarm_id = ? AND run_id = ?
            ORDER BY ts ASC, id ASC
            """;
        List<Map<String, Object>> rows = jdbc.query(sql, ps -> {
            ps.setString(1, cleanedId);
            ps.setString(2, runId);
        }, (rs, rowNum) -> {
            var entry = new LinkedHashMap<String, Object>();
            var ts = rs.getTimestamp("ts");
            entry.put("timestamp", ts == null ? null : ts.toInstant());
            String resolvedSwarmId = rs.getString("swarm_id");
            entry.put("swarmId", resolvedSwarmId);
            entry.put("runId", rs.getString("run_id"));
            entry.put("severity", rs.getString("severity"));
            entry.put("direction", rs.getString("direction"));
            entry.put("kind", rs.getString("kind"));
            entry.put("type", rs.getString("type"));
            entry.put("origin", rs.getString("origin"));
            String role = rs.getString("scope_role");
            String instance = rs.getString("scope_instance");
            entry.put("scope", new ControlScope(resolvedSwarmId, role, instance));
            entry.put("correlationId", rs.getString("correlation_id"));
            entry.put("idempotencyKey", rs.getString("idempotency_key"));
            entry.put("routingKey", rs.getString("routing_key"));
            entry.put("data", parseJsonMap(rs.getString("data")));
            entry.put("raw", parseJsonMap(rs.getString("raw")));
            entry.put("extra", parseJsonMap(rs.getString("extra")));
            return java.util.Collections.unmodifiableMap(entry);
        });
        if (rows.isEmpty() && store.find(cleanedId).isEmpty()) {
            return null;
        }
        return List.copyOf(rows);
    }

    private String resolveActiveRunId(String swarmId, String requestedRunId) {
        String candidate = requestedRunId == null ? null : requestedRunId.trim();
        if (candidate != null && candidate.isBlank()) {
            candidate = null;
        }
        if (candidate != null) {
            return candidate;
        }
        String active = store.find(swarmId)
            .map(Swarm::getRunId)
            .orElse(null);
        if (active != null && !active.isBlank()) {
            return active;
        }
        if (!"postgres".equalsIgnoreCase(journalSink)) {
            return null;
        }
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

    private UUID findPinnedCaptureId(String swarmId, String runId) {
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
        record Stats(java.sql.Timestamp first, java.sql.Timestamp last, long entries) {}
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

    private JournalPageResponse readJournalPageFromArchive(UUID captureId,
                                                          String scope,
                                                          String swarmId,
                                                          String runId,
                                                          String correlationId,
                                                          Instant beforeTs,
                                                          Long beforeId,
                                                          int limit) {
        record Row(long id, Instant ts, Map<String, Object> entry) {}
        StringBuilder sql = new StringBuilder("""
            SELECT
              id,
              ts,
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
              data::text AS data,
              raw::text AS raw,
              extra::text AS extra
            FROM journal_event_archive
            WHERE capture_id = ? AND scope = ? AND swarm_id = ? AND run_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(captureId);
        args.add(scope);
        args.add(swarmId);
        args.add(runId);
        if (correlationId != null) {
            sql.append(" AND correlation_id = ?");
            args.add(correlationId);
        }
        if (beforeTs != null && beforeId != null) {
            sql.append(" AND (ts, id) < (?, ?)");
            args.add(java.sql.Timestamp.from(beforeTs));
            args.add(beforeId);
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
        args.add(limit + 1);

        List<Row> rows = jdbc.query(sql.toString(), args.toArray(), (rs, rowNum) -> {
            long id = rs.getLong("id");
            var entry = new LinkedHashMap<String, Object>();
            var ts = rs.getTimestamp("ts");
            Instant instant = ts == null ? Instant.EPOCH : ts.toInstant();
            entry.put("eventId", id);
            entry.put("timestamp", instant);
            String resolvedSwarmId = rs.getString("swarm_id");
            entry.put("swarmId", resolvedSwarmId);
            entry.put("runId", rs.getString("run_id"));
            entry.put("severity", rs.getString("severity"));
            entry.put("direction", rs.getString("direction"));
            entry.put("kind", rs.getString("kind"));
            entry.put("type", rs.getString("type"));
            entry.put("origin", rs.getString("origin"));
            String role = rs.getString("scope_role");
            String instance = rs.getString("scope_instance");
            entry.put("scope", new ControlScope(resolvedSwarmId, role, instance));
            entry.put("correlationId", rs.getString("correlation_id"));
            entry.put("idempotencyKey", rs.getString("idempotency_key"));
            entry.put("routingKey", rs.getString("routing_key"));
            entry.put("data", parseJsonMap(rs.getString("data")));
            entry.put("raw", parseJsonMap(rs.getString("raw")));
            entry.put("extra", parseJsonMap(rs.getString("extra")));
            return new Row(id, instant, java.util.Collections.unmodifiableMap(entry));
        });

        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        JournalPageResponse.Cursor cursor = null;
        if (hasMore && !rows.isEmpty()) {
            Row last = rows.get(rows.size() - 1);
            cursor = new JournalPageResponse.Cursor(last.ts(), last.id());
        }
        List<Map<String, Object>> items = rows.stream().map(Row::entry).toList();
        return new JournalPageResponse(items, cursor, hasMore);
    }

    private List<Map<String, Object>> readJournalEntriesFromArchive(UUID captureId, String swarmId, String runId) {
        String sql = """
            SELECT
              ts,
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
              data::text AS data,
              raw::text AS raw,
              extra::text AS extra
            FROM journal_event_archive
            WHERE capture_id = ? AND scope = 'SWARM' AND swarm_id = ? AND run_id = ?
            ORDER BY ts ASC, id ASC
            """;
        List<Map<String, Object>> rows = jdbc.query(sql, ps -> {
            ps.setObject(1, captureId);
            ps.setString(2, swarmId);
            ps.setString(3, runId);
        }, (rs, rowNum) -> {
            var entry = new LinkedHashMap<String, Object>();
            var ts = rs.getTimestamp("ts");
            entry.put("timestamp", ts == null ? null : ts.toInstant());
            String resolvedSwarmId = rs.getString("swarm_id");
            entry.put("swarmId", resolvedSwarmId);
            entry.put("runId", rs.getString("run_id"));
            entry.put("severity", rs.getString("severity"));
            entry.put("direction", rs.getString("direction"));
            entry.put("kind", rs.getString("kind"));
            entry.put("type", rs.getString("type"));
            entry.put("origin", rs.getString("origin"));
            String role = rs.getString("scope_role");
            String instance = rs.getString("scope_instance");
            entry.put("scope", new ControlScope(resolvedSwarmId, role, instance));
            entry.put("correlationId", rs.getString("correlation_id"));
            entry.put("idempotencyKey", rs.getString("idempotency_key"));
            entry.put("routingKey", rs.getString("routing_key"));
            entry.put("data", parseJsonMap(rs.getString("data")));
            entry.put("raw", parseJsonMap(rs.getString("raw")));
            entry.put("extra", parseJsonMap(rs.getString("extra")));
            return java.util.Collections.unmodifiableMap(entry);
        });
        return List.copyOf(rows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return null;
        }
        try {
            return json.readValue(jsonText, Map.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String sanitizeSegment(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String cleaned = Paths.get(id).getFileName().toString();
        if (!cleaned.equals(id) || cleaned.contains("..") || cleaned.isBlank()) {
            return null;
        }
        return cleaned;
    }

    public record JournalRunSummary(String runId, Instant firstTs, Instant lastTs, long entries, boolean pinned) {}

    public record PinRunRequest(String runId, String mode, String name) {}

    public record PinRunResponse(String captureId, String swarmId, String runId, String mode, long inserted, long entries) {}

    private enum PinMode {
        FULL,
        SLIM,
        ERRORS_ONLY;

        static PinMode fromNullable(String value) {
            if (value == null || value.isBlank()) {
                return SLIM;
            }
            try {
                return PinMode.valueOf(value.trim().toUpperCase());
            } catch (Exception ignored) {
                return SLIM;
            }
        }
    }

    private void logRestRequest(String method, String path, Object body) {
        log.info("[REST] {} {}", method, path);
        if (log.isDebugEnabled()) {
            log.debug("[REST] {} {} request={}", method, path, toSafeString(body));
        }
    }

    private void logRestResponse(String method, String path, ResponseEntity<?> response) {
        if (response == null) {
            return;
        }
        log.info("[REST] {} {} -> status={}", method, path, response.getStatusCode());
        if (log.isDebugEnabled()) {
            log.debug("[REST] {} {} -> status={} body={}", method, path, response.getStatusCode(), toSafeString(response.getBody()));
        }
    }

    private static String toSafeString(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.length() > 300) {
            return text.substring(0, 300) + "…";
        }
        return text;
    }
}
