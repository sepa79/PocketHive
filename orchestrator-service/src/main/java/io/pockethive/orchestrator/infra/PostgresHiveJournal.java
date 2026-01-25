package io.pockethive.orchestrator.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.journal.postgres.BufferedPostgresJournalWriter;
import io.pockethive.journal.postgres.PostgresJournalBackpressureEvents;
import io.pockethive.journal.postgres.PostgresJournalRecord;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(PostgresHiveJournal.class);
  private static final String HIVE_SWARM_ID = "hive";

  private static final int DEFAULT_CAPACITY = 50_000;
  private static final int DEFAULT_BATCH_SIZE = 1_000;
  private static final long DEFAULT_DB_FAILURE_BACKOFF_MILLIS = 30_000;
  private static final long DEFAULT_DROP_QUIET_PERIOD_MILLIS = 1_000;

  private final ObjectMapper mapper;
  private final SwarmStore store;
  private final BufferedPostgresJournalWriter<HiveJournalEntry> writer;

  public PostgresHiveJournal(ObjectMapper mapper,
                             JdbcTemplate jdbc,
                             SwarmStore store,
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
    this.store = Objects.requireNonNull(store, "store");
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
    Objects.requireNonNull(entry, "entry");
    String swarmId = entry.swarmId();
    if (swarmId == null || swarmId.isBlank()) {
      logMissingRunId("blank swarmId", entry);
      return;
    }
    if (!HIVE_SWARM_ID.equals(swarmId)) {
      String runId = store.find(swarmId)
          .map(io.pockethive.orchestrator.domain.Swarm::getRunId)
          .orElse(null);
      if (runId == null || runId.isBlank()) {
        logMissingRunId("missing runId in store", entry);
        return;
      }
    }
    writer.append(entry);
  }

  @Scheduled(fixedDelay = 200L)
  public void flush() {
    writer.flush();
  }

  private String resolveRunId(HiveJournalEntry entry) {
    String swarmId = entry.swarmId();
    if (HIVE_SWARM_ID.equals(swarmId)) {
      return HIVE_SWARM_ID;
    }
    String runId = store.find(swarmId)
        .map(io.pockethive.orchestrator.domain.Swarm::getRunId)
        .orElse(null);
    if (runId == null || runId.isBlank()) {
      throw new IllegalStateException("runId is required for hive journal entry swarmId=" + swarmId);
    }
    return runId;
  }

  private HiveJournalEntry newDropEvent(String severity, String type, Map<String, Object> data) {
    String swarmId = HIVE_SWARM_ID;
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

  private void logMissingRunId(String reason, HiveJournalEntry entry) {
    // This is a hard reject (no guessing): if runId is missing, journaling that entry is unsafe
    // because Postgres requires run_id and we must not fabricate it.
    log.warn("Dropping hive journal entry ({}): swarmId={} kind={} type={} severity={} origin={} correlationId={} idempotencyKey={}",
        reason,
        entry.swarmId(),
        entry.kind(),
        entry.type(),
        entry.severity(),
        entry.origin(),
        entry.correlationId(),
        entry.idempotencyKey());
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
