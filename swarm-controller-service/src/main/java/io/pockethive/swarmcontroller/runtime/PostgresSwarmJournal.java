package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.swarmcontroller.runtime.SwarmJournal.SwarmJournalEntry;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(PostgresSwarmJournal.class);
  private static final int DEFAULT_CAPACITY = 50_000;
  private static final int DEFAULT_BATCH_SIZE = 1_000;

  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final ArrayBlockingQueue<SwarmJournalEntry> buffer;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong written = new AtomicLong();

  public PostgresSwarmJournal(ObjectMapper mapper, JdbcTemplate jdbc) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.buffer = new ArrayBlockingQueue<>(DEFAULT_CAPACITY);
  }

  @Override
  public void append(SwarmJournalEntry entry) {
    Objects.requireNonNull(entry, "entry");
    // Best-effort: do not block callers.
    if (!buffer.offer(entry)) {
      dropped.incrementAndGet();
    }
  }

  @Scheduled(fixedDelay = 200L)
  public void flush() {
    int maxBatch = Math.min(DEFAULT_BATCH_SIZE, buffer.size());
    java.util.List<SwarmJournalEntry> batch = new ArrayList<>(maxBatch);
    buffer.drainTo(batch, maxBatch);
    int drained = batch.size();
    if (drained == 0) {
      long droppedCount = dropped.getAndSet(0);
      if (droppedCount > 0) {
        log.warn("Swarm journal dropped {} entries due to backpressure", droppedCount);
      }
      return;
    }

    String sql = """
        INSERT INTO journal_event (
          ts,
          scope,
          swarm_id,
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
          data,
          raw,
          extra
        ) VALUES (
          ?,
          'SWARM',
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?,
          ?::jsonb,
          ?::jsonb,
          ?::jsonb
        )
        """;
    try {
      jdbc.batchUpdate(sql, batch, drained, (PreparedStatement ps, SwarmJournalEntry entry) -> {
        Instant ts = entry.timestamp() != null ? entry.timestamp() : Instant.now();
        ps.setTimestamp(1, Timestamp.from(ts));
        ps.setString(2, entry.swarmId());
        ControlScope scope = entry.scope();
        ps.setString(3, scope != null ? scope.role() : null);
        ps.setString(4, scope != null ? scope.instance() : null);
        ps.setString(5, entry.severity());
        ps.setString(6, entry.direction() != null ? entry.direction().name() : null);
        ps.setString(7, entry.kind());
        ps.setString(8, entry.type());
        ps.setString(9, entry.origin());
        ps.setString(10, entry.correlationId());
        ps.setString(11, entry.idempotencyKey());
        ps.setString(12, entry.routingKey());
        ps.setString(13, toJson(entry.data()));
        ps.setString(14, toJson(entry.raw()));
        ps.setString(15, toJson(entry.extra()));
      });
      written.addAndGet(drained);
    } catch (Exception e) {
      log.warn("Swarm journal flush failed; dropping {} entries: {}", drained, e.getMessage());
    }
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
}
