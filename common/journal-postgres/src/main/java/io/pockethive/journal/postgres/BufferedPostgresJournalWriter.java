package io.pockethive.journal.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort, non-blocking buffered writer for {@code journal_event} inserts.
 * <p>
 * Designed for high-volume control-plane journaling: uses a bounded in-memory buffer and periodic
 * batch inserts. When the buffer is full, it drops INFO entries first and attempts to evict INFO
 * entries to make room for WARN/ERROR.
 */
public final class BufferedPostgresJournalWriter<T> {

  private static final Logger log = LoggerFactory.getLogger(BufferedPostgresJournalWriter.class);

  private static final long DEFAULT_DB_FAILURE_BACKOFF_MILLIS = 30_000;
  private static final long DEFAULT_DROP_QUIET_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);

  private static final String INSERT_SQL = """
      INSERT INTO journal_event (
        ts,
        scope,
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
        data,
        raw,
        extra
      ) VALUES (
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
        ?,
        ?,
        ?,
        ?::jsonb,
        ?::jsonb,
        ?::jsonb
      )
      """;

  private final String name;
  private final DataSource dataSource;
  private final ArrayBlockingQueue<T> buffer;
  private final int batchSize;

  private final long dbFailureBackoffMillis;
  private final long dropQuietPeriodNanos;
  private final Function<T, String> severityProvider;
  private final Function<T, PostgresJournalRecord> recordMapper;
  private final PostgresJournalBackpressureEvents<T> backpressureEvents;

  private final AtomicLong droppedInfo = new AtomicLong();
  private final AtomicLong droppedHigh = new AtomicLong();
  private final AtomicBoolean droppingInfo = new AtomicBoolean(false);
  private final AtomicBoolean dropStartedPending = new AtomicBoolean(false);
  private final AtomicBoolean dropStoppedPending = new AtomicBoolean(false);
  private final AtomicLong droppedInfoSinceStart = new AtomicLong();
  private final AtomicLong lastDropNanos = new AtomicLong();

  private final AtomicLong dbDisabledUntilMillis = new AtomicLong();
  private final AtomicLong lastDbWarnMillis = new AtomicLong();

  public BufferedPostgresJournalWriter(String name,
                                      DataSource dataSource,
                                      int capacity,
                                      int batchSize,
                                      Long dbFailureBackoffMillis,
                                      Long dropQuietPeriodMillis,
                                      Function<T, String> severityProvider,
                                      Function<T, PostgresJournalRecord> recordMapper,
                                      PostgresJournalBackpressureEvents<T> backpressureEvents) {
    this.name = requireNonBlank(name, "name");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.batchSize = Math.max(1, batchSize);
    this.buffer = new ArrayBlockingQueue<>(Math.max(1, capacity));
    this.dbFailureBackoffMillis = dbFailureBackoffMillis != null ? Math.max(1, dbFailureBackoffMillis) : DEFAULT_DB_FAILURE_BACKOFF_MILLIS;
    long quietMillis = dropQuietPeriodMillis != null ? Math.max(1, dropQuietPeriodMillis) : TimeUnit.NANOSECONDS.toMillis(DEFAULT_DROP_QUIET_PERIOD_NANOS);
    this.dropQuietPeriodNanos = TimeUnit.MILLISECONDS.toNanos(quietMillis);
    this.severityProvider = Objects.requireNonNull(severityProvider, "severityProvider");
    this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper");
    this.backpressureEvents = backpressureEvents;
  }

  public void append(T entry) {
    Objects.requireNonNull(entry, "entry");
    long nowMillis = System.currentTimeMillis();
    long disabledUntil = dbDisabledUntilMillis.get();
    if (disabledUntil > nowMillis) {
      droppedOnDbFailure(entry, nowMillis);
      return;
    }
    if (buffer.offer(entry)) {
      return;
    }
    if (!isHighSeverity(severityProvider.apply(entry))) {
      droppedInfo.incrementAndGet();
      droppedInfoSinceStart.incrementAndGet();
      lastDropNanos.set(System.nanoTime());
      if (backpressureEvents != null && droppingInfo.compareAndSet(false, true)) {
        dropStartedPending.set(true);
      }
      return;
    }
    if (evictOneInfo()) {
      if (buffer.offer(entry)) {
        return;
      }
    }
    droppedHigh.incrementAndGet();
    lastDropNanos.set(System.nanoTime());
  }

  public void flush() {
    long nowMillis = System.currentTimeMillis();
    long disabledUntil = dbDisabledUntilMillis.get();
    if (disabledUntil > nowMillis) {
      drainAndDrop(nowMillis);
      return;
    }

    if (backpressureEvents != null && droppingInfo.get()) {
      long lastDropAt = lastDropNanos.get();
      if (lastDropAt != 0L && System.nanoTime() - lastDropAt > dropQuietPeriodNanos) {
        droppingInfo.set(false);
        dropStoppedPending.set(true);
      }
    }

    boolean emitInternal = backpressureEvents != null;
    int internalSlots = emitInternal ? (dropStartedPending.get() ? 1 : 0) + (dropStoppedPending.get() ? 1 : 0) : 0;
    int drainedLimit = Math.max(0, Math.min(batchSize - internalSlots, buffer.size()));
    var batch = new ArrayList<T>(internalSlots + drainedLimit);

    if (emitInternal && dropStartedPending.getAndSet(false)) {
      batch.add(backpressureEvents.backpressureStart());
    }
    if (emitInternal && dropStoppedPending.getAndSet(false)) {
      long dropped = droppedInfoSinceStart.getAndSet(0);
      batch.add(backpressureEvents.backpressureStop(dropped));
    }

    if (drainedLimit > 0) {
      buffer.drainTo(batch, drainedLimit);
    }

    if (batch.isEmpty()) {
      long droppedInfoCount = droppedInfo.getAndSet(0);
      long droppedHighCount = droppedHigh.getAndSet(0);
      if (droppedInfoCount > 0 || droppedHighCount > 0) {
        log.warn("{} dropped entries due to backpressure: info={}, high={}", name, droppedInfoCount, droppedHighCount);
      }
      return;
    }

    try {
      insertBatch(batch);
    } catch (Exception e) {
      dbDisabledUntilMillis.set(nowMillis + dbFailureBackoffMillis);
      dropStartedPending.set(false);
      dropStoppedPending.set(false);
      droppingInfo.set(false);
      droppedInfoSinceStart.set(0);
      buffer.clear();
      warnDbFailure(nowMillis, e);
    }
  }

  private void insertBatch(java.util.List<T> batch) throws Exception {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
      for (T entry : batch) {
        PostgresJournalRecord record = recordMapper.apply(entry);
        bind(ps, record);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void bind(PreparedStatement ps, PostgresJournalRecord record) throws Exception {
    Instant ts = record.timestamp() != null ? record.timestamp() : Instant.now();
    ps.setTimestamp(1, Timestamp.from(ts));
    ps.setString(2, record.scope());
    ps.setString(3, record.swarmId());
    ps.setString(4, record.runId());
    ps.setString(5, record.scopeRole());
    ps.setString(6, record.scopeInstance());
    ps.setString(7, record.severity());
    ps.setString(8, record.direction());
    ps.setString(9, record.kind());
    ps.setString(10, record.type());
    ps.setString(11, record.origin());
    ps.setString(12, record.correlationId());
    ps.setString(13, record.idempotencyKey());
    ps.setString(14, record.routingKey());
    ps.setString(15, record.dataJson());
    ps.setString(16, record.rawJson());
    ps.setString(17, record.extraJson());
  }

  private void drainAndDrop(long nowMillis) {
    buffer.clear();
    if (droppingInfo.compareAndSet(true, false)) {
      dropStartedPending.set(false);
      dropStoppedPending.set(false);
      droppedInfoSinceStart.set(0);
    }
    warnDbUnavailable(nowMillis);
  }

  private void droppedOnDbFailure(T entry, long nowMillis) {
    if (!isHighSeverity(severityProvider.apply(entry))) {
      droppedInfo.incrementAndGet();
    } else {
      droppedHigh.incrementAndGet();
    }
    warnDbUnavailable(nowMillis);
  }

  private void warnDbFailure(long nowMillis, Exception e) {
    warnDbUnavailable(nowMillis);
    log.warn("{} flush failed; disabling journaling for {}ms: {}", name, dbFailureBackoffMillis, e.getMessage());
  }

  private void warnDbUnavailable(long nowMillis) {
    long last = lastDbWarnMillis.get();
    if (nowMillis - last < dbFailureBackoffMillis) {
      return;
    }
    if (lastDbWarnMillis.compareAndSet(last, nowMillis)) {
      log.warn("{} DB is unavailable; journaling is temporarily disabled (events will be dropped)", name);
    }
  }

  private boolean evictOneInfo() {
    for (T candidate : buffer) {
      if (candidate != null && !isHighSeverity(severityProvider.apply(candidate))) {
        return buffer.remove(candidate);
      }
    }
    return false;
  }

  private static boolean isHighSeverity(String severity) {
    if (severity == null) {
      return true;
    }
    return !"INFO".equalsIgnoreCase(severity.trim());
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}

