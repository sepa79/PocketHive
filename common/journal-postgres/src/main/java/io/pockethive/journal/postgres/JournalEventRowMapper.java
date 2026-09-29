package io.pockethive.journal.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: decode a SQL journal event into the existing timeline or page projection.
 * Must not: query storage, choose runs or construct next-page cursors.
 * Contract: RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
final class JournalEventRowMapper {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper json;

    JournalEventRowMapper(ObjectMapper json) {
        this.json = json;
    }

    JournalEventRow map(ResultSet rs, boolean paged) throws SQLException {
        long id = rs.getLong("id");
        var ts = rs.getTimestamp("ts");
        Instant timestamp = ts == null ? (paged ? Instant.EPOCH : null) : ts.toInstant();
        var entry = new LinkedHashMap<String, Object>();
        if (paged) {
            entry.put("eventId", id);
        }
        entry.put("timestamp", timestamp);
        String swarmId = rs.getString("swarm_id");
        entry.put("swarmId", swarmId);
        entry.put("runId", rs.getString("run_id"));
        entry.put("severity", rs.getString("severity"));
        entry.put("direction", rs.getString("direction"));
        entry.put("kind", rs.getString("kind"));
        entry.put("type", rs.getString("type"));
        entry.put("origin", rs.getString("origin"));
        entry.put("scope", new ControlScope(swarmId, rs.getString("scope_role"), rs.getString("scope_instance")));
        entry.put("correlationId", rs.getString("correlation_id"));
        entry.put("idempotencyKey", rs.getString("idempotency_key"));
        entry.put("routingKey", rs.getString("routing_key"));
        entry.put("data", parseJsonMap(rs.getString("data")));
        entry.put("raw", parseJsonMap(rs.getString("raw")));
        entry.put("extra", parseJsonMap(rs.getString("extra")));
        return new JournalEventRow(id, timestamp, Collections.unmodifiableMap(entry));
    }

    private Map<String, Object> parseJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return json.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            return null;
        }
    }
}
