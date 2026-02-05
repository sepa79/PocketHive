package io.pockethive.swarmcontroller.runtime;

import io.pockethive.control.ControlScope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort helper for reporting control-plane drops/parsing failures into {@link SwarmJournal}.
 * <p>
 * Control-plane consumers must always ACK and therefore must never throw due to journaling. This helper:
 * <ul>
 *   <li>always swallows journal sink failures</li>
 *   <li>suppresses repeated identical errors for a short quiet period (prevents floods)</li>
 *   <li>emits a {@code suppressedCount} marker when suppressing happened</li>
 * </ul>
 */
public final class SwarmControlPlaneJournalErrors {

  private static final Logger log = LoggerFactory.getLogger(SwarmControlPlaneJournalErrors.class);

  private static final String KIND = "control-plane";

  private static final long DEFAULT_QUIET_PERIOD_MILLIS = 2_000L;
  private static final long JOURNAL_FAILURE_BACKOFF_MILLIS = 30_000L;
  private static final int MAX_SUPPRESSION_KEYS = 10_000;

  private final SwarmJournal journal;
  private final String swarmId;
  private final String origin;
  private final String component;
  private final String instanceId;
  private final long quietPeriodMillis;

  private final ConcurrentHashMap<String, Suppression> suppressions = new ConcurrentHashMap<>();
  private final AtomicLong journalDisabledUntilMillis = new AtomicLong();

  public SwarmControlPlaneJournalErrors(SwarmJournal journal,
                                       String swarmId,
                                       String origin,
                                       String instanceId,
                                       String component) {
    this(journal, swarmId, origin, instanceId, component, DEFAULT_QUIET_PERIOD_MILLIS);
  }

  public SwarmControlPlaneJournalErrors(SwarmJournal journal,
                                       String swarmId,
                                       String origin,
                                       String instanceId,
                                       String component,
                                       long quietPeriodMillis) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.swarmId = requireNonBlank(swarmId, "swarmId");
    this.origin = requireNonBlank(origin, "origin");
    this.instanceId = requireNonBlank(instanceId, "instanceId");
    this.component = requireNonBlank(component, "component");
    this.quietPeriodMillis = Math.max(0, quietPeriodMillis);
  }

  public void errorDrop(String type,
                        String routingKey,
                        String reason,
                        String payload,
                        Exception exception) {
    String resolvedType = requireNonBlank(type, "type");

    long nowMillis = System.currentTimeMillis();
    long disabledUntil = journalDisabledUntilMillis.get();
    if (disabledUntil > nowMillis) {
      return;
    }

    long suppressedCount = maybeSuppress(suppressionKey(resolvedType, routingKey, reason), nowMillis);
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

    ControlScope scope = ControlScope.forInstance(swarmId, "swarm-controller", instanceId);
    try {
      journal.append(new SwarmJournal.SwarmJournalEntry(
          Instant.now(),
          swarmId,
          "ERROR",
          SwarmJournal.Direction.IN,
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
          JOURNAL_FAILURE_BACKOFF_MILLIS, component, resolvedType, swarmId, e);
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

  private static String suppressionKey(String type, String routingKey, String reason) {
    return type + "|" + (routingKey != null ? routingKey : "") + "|" + (reason != null ? reason : "");
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

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static final class Suppression {
    private final AtomicLong lastEmitMillis = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
  }
}

