package io.pockethive.orchestrator.infra;

import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort writer for per-run metadata used by the Journals index UX.
 * <p>
 * This is intentionally decoupled from journal event ingestion: events may arrive before/after metadata,
 * and older runs may have events without metadata.
 */
@Component
public class JournalRunMetadataWriter {

  private static final Logger log = LoggerFactory.getLogger(JournalRunMetadataWriter.class);

  private final JdbcTemplate jdbc;

  public JournalRunMetadataWriter(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public void upsertOnSwarmStart(String swarmId, String runId, SwarmTemplateMetadata templateMetadata) {
    if (swarmId == null || swarmId.isBlank() || runId == null || runId.isBlank()) {
      return;
    }
    String scenarioId = templateMetadata == null ? null : templateMetadata.templateId();
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
}

