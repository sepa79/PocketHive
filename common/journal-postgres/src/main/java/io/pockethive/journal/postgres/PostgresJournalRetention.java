package io.pockethive.journal.postgres;

import io.pockethive.journal.api.JournalRetention;
import io.pockethive.journal.api.JournalRetentionSettings;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Responsibility: maintain daily partitions and prune live journal rows using the configured UTC policy.
 * Must not: schedule itself, write run metadata or delete pinned archives.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
public final class PostgresJournalRetention implements JournalRetention {
  private static final Logger log = LoggerFactory.getLogger(PostgresJournalRetention.class);
  private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
  private static final Pattern PARTITION_NAME = Pattern.compile("^journal_event_(\\d{8})$");

  private final JdbcTemplate jdbc;
  private final int retentionDays;
  private final int createDaysBack;
  private final int createDaysAhead;
  private final int defaultMoveBatchSize;
  private final int defaultMaxFutureDays;

  public PostgresJournalRetention(JdbcTemplate jdbc, JournalRetentionSettings settings) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    this.retentionDays = settings.retentionDays();
    this.createDaysBack = settings.createDaysBack();
    this.createDaysAhead = settings.createDaysAhead();
    this.defaultMoveBatchSize = settings.defaultMoveBatchSize();
    this.defaultMaxFutureDays = settings.defaultMaxFutureDays();
  }

  @Override
  public void reconcile() {
    try {
      ensureDailyPartitions();
      rehomeDefaultPartition();
      pruneDefaultPartition();
      pruneOldPartitions();
    } catch (Exception ex) {
      log.warn("Journal partition reconcile failed: {}", ex.getMessage());
    }
  }

  private void ensureDailyPartitions() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    for (int offset = -createDaysBack; offset <= createDaysAhead; offset++) {
      LocalDate day = today.plusDays(offset);
      createPartitionIfMissing(day);
    }
  }

  private void createPartitionIfMissing(LocalDate day) {
    String name = "journal_event_" + PARTITION_SUFFIX.format(day);
    String from = day + " 00:00:00+00";
    String to = day.plusDays(1) + " 00:00:00+00";
    String sql = """
        CREATE TABLE IF NOT EXISTS %s
        PARTITION OF journal_event
        FOR VALUES FROM (TIMESTAMPTZ '%s') TO (TIMESTAMPTZ '%s')
        """.formatted(name, from, to);
    jdbc.execute(sql);
  }

  private void rehomeDefaultPartition() {
    if (defaultMoveBatchSize <= 0) {
      return;
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate fromDay = today.minusDays(createDaysBack);
    LocalDate toDayExclusive = today.plusDays(createDaysAhead + 1L);
    String fromTs = fromDay + " 00:00:00+00";
    String toTs = toDayExclusive + " 00:00:00+00";
    String sql = """
        WITH moved AS (
          DELETE FROM journal_event_default
          WHERE ctid IN (
            SELECT ctid
            FROM journal_event_default
            WHERE ts >= TIMESTAMPTZ '%s' AND ts < TIMESTAMPTZ '%s'
            ORDER BY ts ASC, id ASC
            LIMIT %d
          )
          RETURNING
            id,
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
        )
        INSERT INTO journal_event (
          id,
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
        )
        SELECT
          id,
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
        FROM moved
        """.formatted(fromTs, toTs, defaultMoveBatchSize);
    int moved = jdbc.update(sql);
    if (moved > 0) {
      log.info("Moved {} journal events from default partition into daily partitions", moved);
    }
  }

  private void pruneDefaultPartition() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate cutoff = today.minusDays(retentionDays);
    String cutoffTs = cutoff + " 00:00:00+00";
    int prunedOld = jdbc.update("DELETE FROM journal_event_default WHERE ts < TIMESTAMPTZ '" + cutoffTs + "'");
    if (prunedOld > 0) {
      log.info("Pruned {} journal events from default partition older than {}", prunedOld, cutoff);
    }

    LocalDate futureCutoff = today.plusDays(defaultMaxFutureDays);
    String futureTs = futureCutoff + " 00:00:00+00";
    int prunedFuture = jdbc.update("DELETE FROM journal_event_default WHERE ts >= TIMESTAMPTZ '" + futureTs + "'");
    if (prunedFuture > 0) {
      log.info("Pruned {} journal events from default partition newer than {}", prunedFuture, futureCutoff);
    }
  }

  private void pruneOldPartitions() {
    LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
    String sql = """
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_inherits i ON i.inhrelid = c.oid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE n.nspname = current_schema()
          AND p.relname = 'journal_event'
        """;
    jdbc.query(sql, rs -> {
      String name = rs.getString(1);
      if (name == null) {
        return;
      }
      Matcher matcher = PARTITION_NAME.matcher(name);
      if (!matcher.matches()) {
        return;
      }
      LocalDate day;
      try {
        day = LocalDate.parse(matcher.group(1), PARTITION_SUFFIX);
      } catch (Exception ignored) {
        return;
      }
      if (!day.isBefore(cutoff)) {
        return;
      }
      String dropSql = "DROP TABLE IF EXISTS " + name;
      try {
        jdbc.execute(dropSql);
        log.info("Dropped journal partition {} (retentionDays={})", name, retentionDays);
      } catch (Exception ex) {
        log.warn("Failed to drop journal partition {}: {}", name, ex.getMessage());
      }
    });
  }
}
