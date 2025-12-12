package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.pockethive.control.ControlScope;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Swarm-scoped journal abstraction.
 * <p>
 * Implementations are responsible for persisting append-only journal entries
 * (for example to a per-swarm JSONL file in the runtime directory).
 */
public interface SwarmJournal {

  void append(SwarmJournalEntry entry);

  /**
   * Convenience factory for a no-op journal, useful for tests or when
   * journaling is disabled.
   */
  static SwarmJournal noop() {
    return entry -> {
      // intentionally no-op
    };
  }

  /**
   * Minimal swarm-level journal entry.
   * <p>
   * This is intentionally generic so it can be projected from existing
   * control-plane envelopes (signals/outcomes/metrics/alerts) and a small
   * number of local projections (e.g. health transitions) without changing
   * on-wire contracts. Fields are chosen to stay replay-friendly.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SwarmJournalEntry(
      Instant timestamp,
      String swarmId,
      String severity,
      Direction direction,
      String kind,
      String type,
      String origin,
      ControlScope scope,
      String correlationId,
      String idempotencyKey,
      String routingKey,
      Map<String, Object> data,
      Map<String, Object> raw,
      Map<String, Object> extra) {

    public SwarmJournalEntry {
      Objects.requireNonNull(timestamp, "timestamp");
      Objects.requireNonNull(swarmId, "swarmId");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(direction, "direction");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(scope, "scope");
      // correlationId, idempotencyKey, routingKey, data, raw and extra are allowed to be null
    }

    public static SwarmJournalEntry info(String swarmId,
                                         Direction direction,
                                         String kind,
                                         String type,
                                         String origin,
                                         ControlScope scope,
                                         String correlationId,
                                         String idempotencyKey,
                                         String routingKey,
                                         Map<String, Object> data,
                                         Map<String, Object> raw,
                                         Map<String, Object> extra) {
      return new SwarmJournalEntry(
          Instant.now(),
          swarmId,
          "INFO",
          direction,
          kind,
          type,
          origin,
          scope,
          correlationId,
          idempotencyKey,
          routingKey,
          data,
          raw,
          extra);
    }

    public static SwarmJournalEntry error(String swarmId,
                                          Direction direction,
                                          String kind,
                                          String type,
                                          String origin,
                                          ControlScope scope,
                                          String correlationId,
                                          String idempotencyKey,
                                          String routingKey,
                                          Map<String, Object> data,
                                          Map<String, Object> raw,
                                          Map<String, Object> extra) {
      return new SwarmJournalEntry(
          Instant.now(),
          swarmId,
          "ERROR",
          direction,
          kind,
          type,
          origin,
          scope,
          correlationId,
          idempotencyKey,
          routingKey,
          data,
          raw,
          extra);
    }
  }

  enum Direction {
    IN,
    OUT,
    LOCAL
  }
}
