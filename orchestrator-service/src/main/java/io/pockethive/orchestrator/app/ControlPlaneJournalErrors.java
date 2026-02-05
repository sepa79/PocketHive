package io.pockethive.orchestrator.app;

import io.pockethive.control.ControlScope;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort helper for reporting control-plane drops/parsing failures into {@link HiveJournal}.
 * <p>
 * Control-plane consumers must always ACK and therefore must never throw due to journaling. This helper:
 * <ul>
 *   <li>always swallows journal sink failures</li>
 *   <li>suppresses repeated identical errors for a short quiet period (prevents floods)</li>
 *   <li>emits a {@code suppressedCount} marker when suppressing happened</li>
 * </ul>
 */
final class ControlPlaneJournalErrors {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneJournalErrors.class);

  private static final String DEFAULT_SWARM_ID = "hive";
  private static final String KIND = "control-plane";

  private static final long DEFAULT_QUIET_PERIOD_MILLIS = 2_000L;
  private static final long JOURNAL_FAILURE_BACKOFF_MILLIS = 30_000L;
  private static final int MAX_SUPPRESSION_KEYS = 10_000;

  private final HiveJournal journal;
  private final String origin;
  private final String component;
  private final long quietPeriodMillis;

  private final ConcurrentHashMap<String, Suppression> suppressions = new ConcurrentHashMap<>();
  private final AtomicLong journalDisabledUntilMillis = new AtomicLong();

  ControlPlaneJournalErrors(HiveJournal journal, String origin, String component) {
    this(journal, origin, component, DEFAULT_QUIET_PERIOD_MILLIS);
  }

  ControlPlaneJournalErrors(HiveJournal journal, String origin, String component, long quietPeriodMillis) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.origin = requireNonBlank(origin, "origin");
    this.component = requireNonBlank(component, "component");
    this.quietPeriodMillis = Math.max(0, quietPeriodMillis);
  }

  void errorDrop(String swarmId,
                 HiveJournal.Direction direction,
                 String type,
                 ControlScope scope,
                 String routingKey,
                 String reason,
                 String payload,
                 Exception exception) {
    String resolvedSwarmId = (swarmId == null || swarmId.isBlank()) ? DEFAULT_SWARM_ID : swarmId;
    String resolvedType = requireNonBlank(type, "type");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(scope, "scope");

    long nowMillis = System.currentTimeMillis();
    long disabledUntil = journalDisabledUntilMillis.get();
    if (disabledUntil > nowMillis) {
      return;
    }

    long suppressedCount = maybeSuppress(
        suppressionKey(resolvedSwarmId, resolvedType, routingKey, reason),
        nowMillis);
    if (suppressedCount < 0) {
      return;
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("component", component);
    data.put("reason", reason != null ? reason : "unknown");
    if (routingKey != null && !routingKey.isBlank()) {
      data.put("routingKey", routingKey);
    }
    if (suppressedCount > 0) {
      data.put("suppressedCount", suppressedCount);
    }
    if (exception != null) {
      data.put("exception", exception.getClass().getSimpleName());
      if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
        data.put("message", exception.getMessage());
      }
    }

    try {
      journal.append(HiveJournalEntry.error(
          resolvedSwarmId,
          direction,
          KIND,
          resolvedType,
          origin,
          scope,
          null,
          null,
          routingKey,
          data,
          Map.of("payloadSnippet", snippet(payload)),
          null));
    } catch (Exception e) {
      journalDisabledUntilMillis.set(nowMillis + JOURNAL_FAILURE_BACKOFF_MILLIS);
      log.error("Disabling control-plane drop journaling for {}ms due to append failure (component={} type={} swarmId={})",
          JOURNAL_FAILURE_BACKOFF_MILLIS, component, resolvedType, resolvedSwarmId, e);
    }
  }

  private long maybeSuppress(String key, long nowMillis) {
    if (quietPeriodMillis <= 0) {
      return 0L;
    }
    if (suppressions.size() > MAX_SUPPRESSION_KEYS) {
      suppressions.clear();
    }
    Suppression suppression = suppressions.computeIfAbsent(key, ignored -> new Suppression());
    long lastEmit = suppression.lastEmitMillis.get();
    if (lastEmit != 0L && nowMillis - lastEmit < quietPeriodMillis) {
      suppression.suppressed.incrementAndGet();
      return -1L;
    }
    suppression.lastEmitMillis.set(nowMillis);
    return suppression.suppressed.getAndSet(0L);
  }

  private static String suppressionKey(String swarmId, String type, String routingKey, String reason) {
    return swarmId + "|" + type + "|" + (routingKey != null ? routingKey : "") + "|" + (reason != null ? reason : "");
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }

  private static String requireNonBlank(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return value.trim();
  }

  private static final class Suppression {
    private final AtomicLong lastEmitMillis = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
  }
}

