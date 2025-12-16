package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import java.util.Map;
import java.util.Objects;

public final class SwarmJournalEntries {

  private SwarmJournalEntries() {
  }

  public static SwarmJournal.SwarmJournalEntry inSignal(ObjectMapper mapper, String routingKey, ControlSignal signal) {
    return fromSignal(mapper, SwarmJournal.Direction.IN, routingKey, signal);
  }

  public static SwarmJournal.SwarmJournalEntry outSignal(ObjectMapper mapper, String routingKey, ControlSignal signal) {
    return fromSignal(mapper, SwarmJournal.Direction.OUT, routingKey, signal);
  }

  public static SwarmJournal.SwarmJournalEntry inOutcome(ObjectMapper mapper, String routingKey, CommandOutcome outcome) {
    return fromOutcome(mapper, SwarmJournal.Direction.IN, routingKey, outcome);
  }

  public static SwarmJournal.SwarmJournalEntry outOutcome(ObjectMapper mapper, String routingKey, CommandOutcome outcome) {
    return fromOutcome(mapper, SwarmJournal.Direction.OUT, routingKey, outcome);
  }

  public static SwarmJournal.SwarmJournalEntry inAlert(ObjectMapper mapper, String routingKey, AlertMessage alert) {
    return fromAlert(mapper, SwarmJournal.Direction.IN, routingKey, alert);
  }

  public static SwarmJournal.SwarmJournalEntry outAlert(ObjectMapper mapper, String routingKey, AlertMessage alert) {
    return fromAlert(mapper, SwarmJournal.Direction.OUT, routingKey, alert);
  }

  public static SwarmJournal.SwarmJournalEntry local(String swarmId,
                                                     String severity,
                                                     String type,
                                                     String origin,
                                                     ControlScope scope,
                                                     Map<String, Object> data,
                                                     Map<String, Object> extra) {
    Objects.requireNonNull(swarmId, "swarmId");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(scope, "scope");
    String kind = "local";
    String normalizedSeverity = severity != null && !severity.isBlank()
        ? severity.trim().toUpperCase(java.util.Locale.ROOT)
        : "INFO";
    return new SwarmJournal.SwarmJournalEntry(
        java.time.Instant.now(),
        swarmId,
        normalizedSeverity,
        SwarmJournal.Direction.LOCAL,
        kind,
        type,
        origin,
        scope,
        null,
        null,
        null,
        data,
        null,
        extra
    );
  }

  private static SwarmJournal.SwarmJournalEntry fromSignal(ObjectMapper mapper,
                                                           SwarmJournal.Direction direction,
                                                           String routingKey,
                                                           ControlSignal signal) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(signal, "signal");
    Map<String, Object> raw = asRaw(mapper, signal);
    return new SwarmJournal.SwarmJournalEntry(
        signal.timestamp(),
        requireSwarmId(signal.scope(), "signal.scope"),
        "INFO",
        direction,
        signal.kind(),
        signal.type(),
        signal.origin(),
        signal.scope(),
        signal.correlationId(),
        signal.idempotencyKey(),
        routingKey,
        signal.data(),
        raw,
        null
    );
  }

  private static SwarmJournal.SwarmJournalEntry fromOutcome(ObjectMapper mapper,
                                                            SwarmJournal.Direction direction,
                                                            String routingKey,
                                                            CommandOutcome outcome) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(outcome, "outcome");
    Map<String, Object> raw = asRaw(mapper, outcome);
    return new SwarmJournal.SwarmJournalEntry(
        outcome.timestamp(),
        requireSwarmId(outcome.scope(), "outcome.scope"),
        "INFO",
        direction,
        outcome.kind(),
        outcome.type(),
        outcome.origin(),
        outcome.scope(),
        outcome.correlationId(),
        outcome.idempotencyKey(),
        routingKey,
        outcome.data(),
        raw,
        null
    );
  }

  private static SwarmJournal.SwarmJournalEntry fromAlert(ObjectMapper mapper,
                                                          SwarmJournal.Direction direction,
                                                          String routingKey,
                                                          AlertMessage alert) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(alert, "alert");
    Map<String, Object> raw = asRaw(mapper, alert);
    return new SwarmJournal.SwarmJournalEntry(
        alert.timestamp(),
        requireSwarmId(alert.scope(), "alert.scope"),
        "ERROR",
        direction,
        alert.kind(),
        alert.type(),
        alert.origin(),
        alert.scope(),
        alert.correlationId(),
        alert.idempotencyKey(),
        routingKey,
        alert.data() != null ? asRaw(mapper, alert.data()) : null,
        raw,
        null
    );
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asRaw(ObjectMapper mapper, Object value) {
    return mapper.convertValue(value, Map.class);
  }

  private static String requireSwarmId(ControlScope scope, String label) {
    if (scope == null || scope.swarmId() == null || scope.swarmId().isBlank()) {
      throw new IllegalArgumentException(label + ".swarmId must not be blank");
    }
    return scope.swarmId();
  }
}
