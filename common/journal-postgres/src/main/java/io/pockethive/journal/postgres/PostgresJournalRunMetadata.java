package io.pockethive.journal.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.SwarmRunMetadataUpdate;
import io.pockethive.journal.api.SwarmRunSummary;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Responsibility: write startup and operator metadata through one SQL owner.
 * Must not: authorize HTTP or reconstruct summary projections.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public final class PostgresJournalRunMetadata implements JournalRunMetadata {
  private static final Logger log = LoggerFactory.getLogger(PostgresJournalRunMetadata.class);
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final JournalRunQueries runs;

  public PostgresJournalRunMetadata(JdbcTemplate jdbc, ObjectMapper json, JournalRunQueries runs) {
    this.jdbc = jdbc;
    this.json = json;
    this.runs = runs;
  }

  @Override
  public void register(String swarmId, String runId, String scenarioId) {
    if (swarmId == null || swarmId.isBlank() || runId == null || runId.isBlank()) {
      return;
    }
    if (scenarioId != null && scenarioId.isBlank()) {
      scenarioId = null;
    }
    String sql = """
        INSERT INTO journal_run (swarm_id, run_id, scenario_id, updated_at)
        VALUES (?, ?, ?, now())
        ON CONFLICT (swarm_id, run_id) DO UPDATE SET
          scenario_id = COALESCE(EXCLUDED.scenario_id, journal_run.scenario_id),
          updated_at = now()
        """;
    try {
      jdbc.update(sql, swarmId, runId, scenarioId);
    } catch (Exception ex) {
      log.warn("Failed to upsert journal run metadata for swarm {} (runId={}): {}", swarmId, runId, ex.getMessage());
    }
  }
  @Override
  public SwarmRunSummary update(String resolvedRunId, SwarmRunMetadataUpdate update) throws Exception {
    SwarmRunMetadataUpdate cleaned = JournalMetadataNormalizer.clean(update);
    String swarmId = resolveSingleSwarmIdForRun(resolvedRunId);
    if (swarmId == null) {
      return null;
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
    return runs.metadataSummary(swarmId, resolvedRunId);
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

}
