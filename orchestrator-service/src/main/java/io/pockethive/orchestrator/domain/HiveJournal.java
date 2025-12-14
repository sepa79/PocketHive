package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.pockethive.control.ControlScope;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Hive-scoped journal abstraction.
 * <p>
 * This is a high-signal projection over orchestrator lifecycle actions and
 * outcomes (e.g. swarm create/start/stop/remove/template) intended for
 * dashboarding and weekend "what happened" reviews.
 */
public interface HiveJournal {

  void append(HiveJournalEntry entry);

  static HiveJournal noop() {
    return entry -> {
      // intentionally no-op
    };
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record HiveJournalEntry(
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

    public HiveJournalEntry {
      Objects.requireNonNull(timestamp, "timestamp");
      Objects.requireNonNull(swarmId, "swarmId");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(direction, "direction");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(scope, "scope");
    }

    public static HiveJournalEntry info(String swarmId,
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
      return new HiveJournalEntry(
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

    public static HiveJournalEntry error(String swarmId,
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
      return new HiveJournalEntry(
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

