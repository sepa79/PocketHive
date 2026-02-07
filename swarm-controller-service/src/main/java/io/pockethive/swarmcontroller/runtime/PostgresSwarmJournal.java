package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.journal.postgres.BufferedPostgresJournalWriter;
import io.pockethive.journal.postgres.PostgresJournalBackpressureEvents;
import io.pockethive.journal.postgres.PostgresJournalRecord;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Postgres-backed implementation of {@link SwarmJournal}.
 * <p>
 * Uses a bounded in-memory buffer with periodic batch inserts so journaling does not block
 * control-plane processing under load.
 */
@Component
@ConditionalOnProperty(name = "pockethive.journal.sink", havingValue = "postgres")
public class PostgresSwarmJournal implements SwarmJournal {

  private static final int DEFAULT_CAPACITY = 50_000;
  private static final int DEFAULT_BATCH_SIZE = 1_000;
  private static final long DEFAULT_DB_FAILURE_BACKOFF_MILLIS = 30_000;
  private static final long DEFAULT_DROP_QUIET_PERIOD_MILLIS = 1_000;

  private final ObjectMapper mapper;
  private final String swarmId;
  private final String runId;
  private final BufferedPostgresJournalWriter<SwarmJournalEntry> writer;

  public PostgresSwarmJournal(ObjectMapper mapper,
                              JdbcTemplate jdbc,
                              @Value("${pockethive.control-plane.swarm-id}") String swarmId,
                              @Value("${pockethive.journal.run-id}") String runId,
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
    this.swarmId = requireNonBlank(swarmId, "swarmId");
    this.runId = requireNonBlank(runId, "runId");
    this.writer = new BufferedPostgresJournalWriter<>(
        "Swarm journal",
        Objects.requireNonNull(jdbc.getDataSource(), "dataSource"),
        capacity,
        batchSize,
        dbFailureBackoffMillis,
        dropQuietPeriodMillis,
        SwarmJournalEntry::severity,
        this::toRecord,
        new SwarmBackpressureEvents());
  }

  @Override
  public void append(SwarmJournalEntry entry) {
    writer.append(entry);
  }

  @Scheduled(fixedDelay = 200L)
  public void flush() {
    writer.flush();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private SwarmJournalEntry newDropEvent(String severity, String type, Map<String, Object> data) {
    ControlScope scope = new ControlScope(swarmId, "swarm-controller", "journal");
    return new SwarmJournalEntry(
        Instant.now(),
        swarmId,
        severity,
        Direction.LOCAL,
        "infra",
        type,
        "swarm-controller",
        scope,
        null,
        null,
        null,
        data,
        null,
        null);
  }

  private PostgresJournalRecord toRecord(SwarmJournalEntry entry) {
    ControlScope scope = entry.scope();
    return new PostgresJournalRecord(
        entry.timestamp() != null ? entry.timestamp() : Instant.now(),
        "SWARM",
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

  private final class SwarmBackpressureEvents implements PostgresJournalBackpressureEvents<SwarmJournalEntry> {

    @Override
    public SwarmJournalEntry backpressureStart() {
      return newDropEvent("WARN", "journal-backpressure-start", Map.of("policy", "dropping INFO"));
    }

    @Override
    public SwarmJournalEntry backpressureStop(long droppedInfo) {
      return newDropEvent("WARN", "journal-backpressure-stop", Map.of("droppedInfo", droppedInfo));
    }
  }
}
