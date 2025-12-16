package io.pockethive.orchestrator.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.journal.postgres.BufferedPostgresJournalWriter;
import io.pockethive.journal.postgres.PostgresJournalBackpressureEvents;
import io.pockethive.journal.postgres.PostgresJournalRecord;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Postgres-backed implementation of {@link HiveJournal}.
 * <p>
 * Uses a bounded in-memory buffer with periodic batch inserts so journaling does not block
 * orchestrator request/control-plane handling under load.
 */
@Component
@ConditionalOnProperty(name = "pockethive.journal.sink", havingValue = "postgres")
public class PostgresHiveJournal implements HiveJournal {

  private static final int DEFAULT_CAPACITY = 50_000;
  private static final int DEFAULT_BATCH_SIZE = 1_000;
  private static final long DEFAULT_DB_FAILURE_BACKOFF_MILLIS = 30_000;
  private static final long DEFAULT_DROP_QUIET_PERIOD_MILLIS = 1_000;

  private final ObjectMapper mapper;
  private final SwarmRegistry registry;
  private final BufferedPostgresJournalWriter<HiveJournalEntry> writer;

  public PostgresHiveJournal(ObjectMapper mapper,
                             JdbcTemplate jdbc,
                             SwarmRegistry registry,
                             @Value("${pockethive.journal.postgres.buffer-capacity:" + DEFAULT_CAPACITY + "}")
                             int capacity,
                             @Value("${pockethive.journal.postgres.batch-size:" + DEFAULT_BATCH_SIZE + "}")
                             int batchSize,
                             @Value("${pockethive.journal.postgres.db-failure-backoff-ms:" + DEFAULT_DB_FAILURE_BACKOFF_MILLIS + "}")
                             long dbFailureBackoffMillis,
                             @Value("${pockethive.journal.postgres.drop-quiet-period-ms:" + DEFAULT_DROP_QUIET_PERIOD_MILLIS + "}")
                             long dropQuietPeriodMillis) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    Objects.requireNonNull(jdbc, "jdbc");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.writer = new BufferedPostgresJournalWriter<>(
        "Hive journal",
        Objects.requireNonNull(jdbc.getDataSource(), "dataSource"),
        capacity,
        batchSize,
        dbFailureBackoffMillis,
        dropQuietPeriodMillis,
        HiveJournalEntry::severity,
        this::toRecord,
        new HiveBackpressureEvents());
  }

  @Override
  public void append(HiveJournalEntry entry) {
    writer.append(entry);
  }

  @Scheduled(fixedDelay = 200L)
  public void flush() {
    writer.flush();
  }

  private String resolveRunId(HiveJournalEntry entry) {
    String swarmId = entry.swarmId();
    if (swarmId == null || swarmId.isBlank()) {
      return "legacy";
    }
    return registry.find(swarmId)
        .map(io.pockethive.orchestrator.domain.Swarm::getRunId)
        .filter(id -> id != null && !id.isBlank())
        .orElse("legacy");
  }

  private HiveJournalEntry newDropEvent(String severity, String type, Map<String, Object> data) {
    String swarmId = "hive";
    ControlScope scope = new ControlScope(swarmId, "orchestrator", "journal");
    return new HiveJournalEntry(
        Instant.now(),
        swarmId,
        severity,
        Direction.LOCAL,
        "infra",
        type,
        "orchestrator",
        scope,
        null,
        null,
        null,
        data,
        null,
        null);
  }

  private PostgresJournalRecord toRecord(HiveJournalEntry entry) {
    String runId = resolveRunId(entry);
    ControlScope scope = entry.scope();
    return new PostgresJournalRecord(
        entry.timestamp() != null ? entry.timestamp() : Instant.now(),
        "HIVE",
        entry.swarmId(),
        runId,
        scope != null ? scope.role() : null,
        scope != null ? scope.instance() : null,
        entry.severity(),
        entry.direction() != null ? entry.direction().name() : null,
        entry.kind(),
        entry.type(),
        entry.origin(),
        entry.correlationId(),
        entry.idempotencyKey(),
        entry.routingKey(),
        toJson(entry.data()),
        toJson(entry.raw()),
        toJson(entry.extra()));
  }

  private String toJson(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      return null;
    }
  }

  private final class HiveBackpressureEvents implements PostgresJournalBackpressureEvents<HiveJournalEntry> {

    @Override
    public HiveJournalEntry backpressureStart() {
      return newDropEvent("WARN", "journal-backpressure-start", Map.of("policy", "dropping INFO"));
    }

    @Override
    public HiveJournalEntry backpressureStop(long droppedInfo) {
      return newDropEvent("WARN", "journal-backpressure-stop", Map.of("droppedInfo", droppedInfo));
    }
  }
}
