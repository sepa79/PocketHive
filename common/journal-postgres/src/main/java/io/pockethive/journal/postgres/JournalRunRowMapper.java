package io.pockethive.journal.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.SwarmRunSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Responsibility: decode stored run summaries and their persisted tag arrays.
 * Must not: query storage, merge summaries or normalize metadata write requests.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries.
 */
final class JournalRunRowMapper {
    private final ObjectMapper json;

    JournalRunRowMapper(ObjectMapper json) {
        this.json = json;
    }

    SwarmRunSummary full(ResultSet rs, boolean pinned) throws SQLException {
        return map(rs, rs.getString("swarm_id"), pinned, true);
    }

    SwarmRunSummary forSwarm(ResultSet rs, String swarmId, boolean pinned) throws SQLException {
        return map(rs, swarmId, pinned, false);
    }

    private SwarmRunSummary map(ResultSet rs, String swarmId, boolean pinned, boolean metadata) throws SQLException {
        var first = rs.getTimestamp("first_ts");
        var last = rs.getTimestamp("last_ts");
        return new SwarmRunSummary(swarmId, rs.getString("run_id"),
            first == null ? null : first.toInstant(), last == null ? null : last.toInstant(),
            rs.getLong("entries"), pinned,
            metadata ? rs.getString("scenario_id") : null,
            metadata ? rs.getString("test_plan") : null,
            metadata ? parseJsonStringList(rs.getString("tags")) : null,
            metadata ? rs.getString("description") : null);
    }

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

}
