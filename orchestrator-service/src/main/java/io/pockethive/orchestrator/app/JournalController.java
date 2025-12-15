package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import java.time.Instant;
import java.util.ArrayList;
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
    if (resolvedRunId == null && cleanedSwarmId != null) {
      resolvedRunId = registry.find(cleanedSwarmId)
          .map(io.pockethive.orchestrator.domain.Swarm::getRunId)
          .filter(id -> id != null && !id.isBlank())
          .orElse(null);
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
    String cleaned = java.nio.file.Paths.get(id).getFileName().toString();
    if (!cleaned.equals(id) || cleaned.contains("..") || cleaned.isBlank()) {
      return null;
    }
    return cleaned;
  }
}
