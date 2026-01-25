package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journal")
public class JournalController {

  private static final Logger log = LoggerFactory.getLogger(JournalController.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final SwarmRegistry registry;

  @Value("${pockethive.journal.sink:postgres}")
  private String journalSink;

  public JournalController(JdbcTemplate jdbc, ObjectMapper json, SwarmRegistry registry) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json").findAndRegisterModules();
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /**
   * GET {@code /api/journal/hive/page} — paginated Hive journal query (Postgres only).
   * <p>
   * Results are returned newest-first (descending by {@code (ts, id)}). Use {@code nextCursor} as the
   * {@code beforeTs}/{@code beforeId} pair for the next page (older entries).
   */
  @GetMapping("/hive/page")
  public ResponseEntity<JournalPageResponse> hiveJournalPage(
      @RequestParam(required = false) String swarmId,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) String correlationId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant beforeTs,
      @RequestParam(required = false) Long beforeId,
      @RequestParam(required = false) Integer limit) {
    String path = "/api/journal/hive/page";
    log.info("[REST] GET {}", path);
    if (!"postgres".equalsIgnoreCase(journalSink)) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
    if ((beforeTs == null) != (beforeId == null)) {
      return ResponseEntity.badRequest().build();
    }
    int pageSize = limit == null ? 200 : Math.max(1, Math.min(1000, limit));
    String cleanedSwarmId = swarmId == null ? null : sanitizeSegment(swarmId.trim());
    String resolvedRunId = runId == null ? null : runId.trim();
    if (resolvedRunId != null && resolvedRunId.isBlank()) {
      resolvedRunId = null;
    }
    String corr = correlationId == null ? null : correlationId.trim();
    if (corr != null && corr.isBlank()) {
      corr = null;
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
        WHERE scope = 'HIVE'
        """);
    List<Object> args = new ArrayList<>();
    if (cleanedSwarmId != null) {
      sql.append(" AND swarm_id = ?");
      args.add(cleanedSwarmId);
    }
    if (resolvedRunId != null) {
      sql.append(" AND run_id = ?");
      args.add(resolvedRunId);
    }
    if (corr != null) {
      sql.append(" AND correlation_id = ?");
      args.add(corr);
    }
    if (beforeTs != null && beforeId != null) {
      sql.append(" AND (ts, id) < (?, ?)");
      args.add(java.sql.Timestamp.from(beforeTs));
      args.add(beforeId);
    }
    sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
    args.add(pageSize + 1);

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

    boolean hasMore = rows.size() > pageSize;
    if (hasMore) {
      rows = rows.subList(0, pageSize);
    }
    JournalPageResponse.Cursor cursor = null;
    if (hasMore && !rows.isEmpty()) {
      Row last = rows.get(rows.size() - 1);
      cursor = new JournalPageResponse.Cursor(last.ts(), last.id());
    }
    List<Map<String, Object>> items = rows.stream().map(Row::entry).toList();
    return ResponseEntity.ok(new JournalPageResponse(items, cursor, hasMore));
  }

  /**
   * GET {@code /api/journal/swarm/runs} — list all known swarm journal runs (Postgres only).
   * <p>
   * Runs are returned newest-first (by last entry time). Pinned runs (archives) are included even when the
   * main timeline has been truncated by retention.
   */
  @GetMapping("/swarm/runs")
  public ResponseEntity<List<SwarmRunSummary>> swarmJournalRuns(@RequestParam(required = false) Integer limit,
                                                                @RequestParam(required = false) Boolean pinned,
                                                                @RequestParam(required = false)
                                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                                Instant afterTs) {
    String path = "/api/journal/swarm/runs";
    log.info("[REST] GET {}", path);
    if (!"postgres".equalsIgnoreCase(journalSink)) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
    int pageSize = limit == null ? 500 : Math.max(1, Math.min(5000, limit));
    boolean pinnedOnly = pinned != null && pinned;

    record Key(String swarmId, String runId) {}

    try {
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
      List<SwarmRunSummary> pinnedRuns = jdbc.query(captureSql, (rs, rowNum) -> {
        String swarmId = rs.getString("swarm_id");
        String runId = rs.getString("run_id");
        java.sql.Timestamp first = rs.getTimestamp("first_ts");
        java.sql.Timestamp last = rs.getTimestamp("last_ts");
        long entries = rs.getLong("entries");
        String scenarioId = rs.getString("scenario_id");
        String testPlan = rs.getString("test_plan");
        String description = rs.getString("description");
        List<String> tags = parseJsonStringList(rs.getString("tags"));
        return new SwarmRunSummary(
            swarmId,
            runId,
            first == null ? null : first.toInstant(),
            last == null ? null : last.toInstant(),
            entries,
            true,
            scenarioId,
            testPlan,
            tags,
            description);
      });

      if (pinnedOnly) {
        List<SwarmRunSummary> runs = pinnedRuns.stream()
            .filter(run -> run.runId() != null && run.swarmId() != null)
            .limit(pageSize)
            .toList();
        return ResponseEntity.ok(runs);
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
      }, (rs, rowNum) -> {
        String swarmId = rs.getString("swarm_id");
        String runId = rs.getString("run_id");
        java.sql.Timestamp first = rs.getTimestamp("first_ts");
        java.sql.Timestamp last = rs.getTimestamp("last_ts");
        long entries = rs.getLong("entries");
        String scenarioId = rs.getString("scenario_id");
        String testPlan = rs.getString("test_plan");
        String description = rs.getString("description");
        List<String> tags = parseJsonStringList(rs.getString("tags"));
        return new SwarmRunSummary(
            swarmId,
            runId,
            first == null ? null : first.toInstant(),
            last == null ? null : last.toInstant(),
            entries,
            false,
            scenarioId,
            testPlan,
            tags,
            description);
      });

      Map<Key, SwarmRunSummary> merged = new LinkedHashMap<>();
      for (SwarmRunSummary run : pinnedRuns) {
        if (run.runId() != null && run.swarmId() != null) {
          merged.put(new Key(run.swarmId(), run.runId()), run);
        }
      }
      for (SwarmRunSummary run : mainRuns) {
        if (run.runId() == null || run.swarmId() == null) {
          continue;
        }
        Key key = new Key(run.swarmId(), run.runId());
        SwarmRunSummary existing = merged.get(key);
        if (existing == null) {
          merged.put(key, run);
        } else if (existing.lastTs() == null || (run.lastTs() != null && run.lastTs().isAfter(existing.lastTs()))) {
          String scenarioId = existing.scenarioId() != null ? existing.scenarioId() : run.scenarioId();
          String testPlan = existing.testPlan() != null ? existing.testPlan() : run.testPlan();
          List<String> tags = existing.tags() != null ? existing.tags() : run.tags();
          String description = existing.description() != null ? existing.description() : run.description();
          merged.put(key, new SwarmRunSummary(
              existing.swarmId(),
              existing.runId(),
              existing.firstTs() != null ? existing.firstTs() : run.firstTs(),
              run.lastTs(),
              Math.max(existing.entries(), run.entries()),
              true,
              scenarioId,
              testPlan,
              tags,
              description));
        }
      }

      List<SwarmRunSummary> runs = merged.values().stream()
          .sorted(Comparator.comparing(SwarmRunSummary::lastTs, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
          .limit(pageSize)
          .toList();
      return ResponseEntity.ok(runs);
    } catch (Exception ex) {
      log.warn("Failed to list swarm journal runs: {}", ex.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * POST {@code /api/journal/swarm/runs/{runId}/meta} — set/clear metadata for a run (Postgres only).
   * <p>
   * This exists so operators/tools can label runs (test plan, tags, notes) without issuing SQL manually.
   */
  @PostMapping("/swarm/runs/{runId}/meta")
  public ResponseEntity<SwarmRunSummary> updateRunMetadata(@PathVariable String runId,
                                                           @RequestBody(required = false) SwarmRunMetadataUpdate update) {
    String path = "/api/journal/swarm/runs/" + runId + "/meta";
    log.info("[REST] POST {}", path);
    if (!"postgres".equalsIgnoreCase(journalSink)) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
    String resolvedRunId = runId == null ? null : runId.trim();
    if (resolvedRunId == null || resolvedRunId.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    SwarmRunMetadataUpdate cleaned = SwarmRunMetadataUpdate.clean(update);
    try {
      String swarmId = resolveSingleSwarmIdForRun(resolvedRunId);
      if (swarmId == null) {
        return ResponseEntity.notFound().build();
      }
      String tagsJson = cleaned.tags() == null ? null : json.writeValueAsString(cleaned.tags());
      String sql = """
          INSERT INTO journal_run (swarm_id, run_id, test_plan, description, tags, updated_at)
          VALUES (?, ?, ?, ?, ?::jsonb, now())
          ON CONFLICT (swarm_id, run_id) DO UPDATE SET
            test_plan = EXCLUDED.test_plan,
            description = EXCLUDED.description,
            tags = EXCLUDED.tags,
            updated_at = now()
          """;
      jdbc.update(sql, swarmId, resolvedRunId, cleaned.testPlan(), cleaned.description(), tagsJson);
      // Return the updated summary row (best-effort: if the run has no events anymore, return just metadata).
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
      SwarmRunSummary summary = jdbc.queryForObject(summarySql, (rs, rowNum) -> {
        java.sql.Timestamp first = rs.getTimestamp("first_ts");
        java.sql.Timestamp last = rs.getTimestamp("last_ts");
        List<String> tags = parseJsonStringList(rs.getString("tags"));
        return new SwarmRunSummary(
            rs.getString("swarm_id"),
            rs.getString("run_id"),
            first == null ? null : first.toInstant(),
            last == null ? null : last.toInstant(),
            rs.getLong("entries"),
            false,
            rs.getString("scenario_id"),
            rs.getString("test_plan"),
            tags,
            rs.getString("description"));
      }, swarmId, resolvedRunId);
      return ResponseEntity.ok(summary);
    } catch (IllegalStateException conflict) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (Exception ex) {
      log.warn("Failed to update run metadata for runId={}: {}", resolvedRunId, ex.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
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

  @SuppressWarnings("unchecked")
  private List<String> parseJsonStringList(String jsonText) {
    if (jsonText == null || jsonText.isBlank()) {
      return null;
    }
    try {
      Object value = json.readValue(jsonText, Object.class);
      if (!(value instanceof List<?> list)) {
        return null;
      }
      List<String> out = new ArrayList<>();
      for (Object entry : list) {
        if (entry instanceof String s) {
          String trimmed = s.trim();
          if (!trimmed.isBlank() && !out.contains(trimmed)) {
            out.add(trimmed);
          }
        }
      }
      return out.isEmpty() ? null : java.util.Collections.unmodifiableList(out);
    } catch (Exception ex) {
      return null;
    }
  }

  private String resolveSingleSwarmIdForRun(String runId) {
    List<String> swarmIds = jdbc.query(
        "SELECT DISTINCT swarm_id FROM journal_run WHERE run_id = ? LIMIT 2",
        ps -> ps.setString(1, runId),
        (rs, rowNum) -> rs.getString(1));
    if (swarmIds.isEmpty()) {
      swarmIds = jdbc.query(
          "SELECT DISTINCT swarm_id FROM journal_event WHERE run_id = ? LIMIT 2",
          ps -> ps.setString(1, runId),
          (rs, rowNum) -> rs.getString(1));
    }
    if (swarmIds.isEmpty()) {
      swarmIds = jdbc.query(
          "SELECT DISTINCT swarm_id FROM journal_capture WHERE run_id = ? LIMIT 2",
          ps -> ps.setString(1, runId),
          (rs, rowNum) -> rs.getString(1));
    }
    if (swarmIds.isEmpty()) {
      return null;
    }
    if (swarmIds.size() > 1) {
      throw new IllegalStateException("runId mapped to multiple swarms");
    }
    String swarmId = swarmIds.get(0);
    return swarmId == null || swarmId.isBlank() ? null : swarmId;
  }

  private static String sanitizeSegment(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    String cleaned = java.nio.file.Paths.get(id).getFileName().toString();
    if (!cleaned.equals(id) || cleaned.contains("..") || cleaned.isBlank()) {
      return null;
    }
    return cleaned;
  }

  public record SwarmRunSummary(
      String swarmId,
      String runId,
      Instant firstTs,
      Instant lastTs,
      long entries,
      boolean pinned,
      String scenarioId,
      String testPlan,
      List<String> tags,
      String description) {}

  public record SwarmRunMetadataUpdate(String testPlan, String description, List<String> tags) {

    static SwarmRunMetadataUpdate clean(SwarmRunMetadataUpdate input) {
      if (input == null) {
        return new SwarmRunMetadataUpdate(null, null, null);
      }
      String testPlan = input.testPlan();
      if (testPlan != null) {
        testPlan = testPlan.trim();
        if (testPlan.isBlank()) {
          testPlan = null;
        }
      }
      String description = input.description();
      if (description != null) {
        description = description.trim();
        if (description.isBlank()) {
          description = null;
        }
      }
      List<String> tags = input.tags();
      if (tags != null) {
        List<String> out = new ArrayList<>();
        for (String tag : tags) {
          if (tag == null) {
            continue;
          }
          String trimmed = tag.trim();
          if (trimmed.isBlank()) {
            continue;
          }
          if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
          }
          if (!out.contains(trimmed)) {
            out.add(trimmed);
          }
          if (out.size() >= 32) {
            break;
          }
        }
        tags = out.isEmpty() ? null : java.util.Collections.unmodifiableList(out);
      }
      return new SwarmRunMetadataUpdate(testPlan, description, tags);
    }
  }
}
