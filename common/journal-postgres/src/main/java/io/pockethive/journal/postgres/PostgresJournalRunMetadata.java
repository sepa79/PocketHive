package io.pockethive.journal.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.SwarmRunMetadataUpdate;
import io.pockethive.journal.api.SwarmRunSummary;
import io.pockethive.journal.api.PlannerRunLink;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    return transaction(() -> {
      jdbc.update("INSERT INTO journal_run (swarm_id, run_id) VALUES (?, ?) ON CONFLICT DO NOTHING", swarmId, resolvedRunId);
      SwarmRunMetadataUpdate current = locked(swarmId, resolvedRunId);
      if (!Objects.equals(current.testPlan(), cleaned.testPlan())
          && (isPlannerPlan(current.testPlan()) || isPlannerPlan(cleaned.testPlan()))) {
        throw new IllegalStateException("Planner plan references must be assigned through PocketPlanner selection");
      }
      if (!plannerTags(current.tags()).equals(plannerTags(cleaned.tags()))) {
        throw new IllegalStateException("Planner Activity associations cannot be replaced by generic metadata edits");
      }
      jdbc.update(sql, swarmId, resolvedRunId, cleaned.testPlan(), cleaned.description(), tagsJson);
      return runs.metadataSummary(swarmId, resolvedRunId);
    });
  }

  @Override
  public SwarmRunSummary linkPlanner(String swarmId, String runId, PlannerRunLink link) {
    Objects.requireNonNull(link, "link");
    return transaction(() -> {
      SwarmRunMetadataUpdate current = locked(swarmId, runId);
      if (current == null) return null;
      if (current.testPlan() != null && !current.testPlan().equals(link.testPlan())) {
        throw new IllegalStateException("This run already belongs to another test plan");
      }
      List<String> tags = new ArrayList<>(current.tags() == null ? List.of() : current.tags());
      if (!tags.contains(link.activityTag())) tags.add(link.activityTag());
      if (tags.size() > 32 || tags.stream().anyMatch(tag -> tag == null || tag.length() > 64)) {
        throw new IllegalStateException("Run tag limit exceeded; no tags were removed or shortened");
      }
      String tagsJson;
      try {
        tagsJson = json.writeValueAsString(tags);
      } catch (Exception exception) {
        throw new IllegalStateException("Cannot encode run tags", exception);
      }
      jdbc.update("UPDATE journal_run SET test_plan = ?, tags = ?::jsonb, updated_at = now() WHERE swarm_id = ? AND run_id = ?",
          link.testPlan(), tagsJson, swarmId, runId);
      return runs.metadataSummary(swarmId, runId);
    });
  }

  private <Result> Result transaction(Supplier<Result> action) {
    return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
        .execute(status -> action.get());
  }

  private SwarmRunMetadataUpdate locked(String swarmId, String runId) {
    List<SwarmRunMetadataUpdate> rows = jdbc.query(
        "SELECT test_plan, description, tags::text FROM journal_run WHERE swarm_id = ? AND run_id = ? FOR UPDATE",
        (result, index) -> {
          String tags = result.getString(3);
          try {
            return new SwarmRunMetadataUpdate(result.getString(1), result.getString(2),
                tags == null ? null : json.readValue(tags, new TypeReference<List<String>>() {}));
          } catch (Exception exception) {
            throw new IllegalStateException("Invalid persisted run tags", exception);
          }
        }, swarmId, runId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static boolean isPlannerPlan(String value) {
    return value != null && value.startsWith(PlannerRunLink.PLAN_PREFIX);
  }

  private static java.util.Set<String> plannerTags(List<String> tags) {
    return tags == null ? java.util.Set.of() : tags.stream()
        .filter(tag -> tag.startsWith(PlannerRunLink.ACTIVITY_PREFIX)).collect(Collectors.toSet());
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
