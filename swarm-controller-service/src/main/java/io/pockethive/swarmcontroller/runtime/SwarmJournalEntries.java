package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import java.util.Map;
import java.util.Objects;

public final class SwarmJournalEntries {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private SwarmJournalEntries() {
  }

  public static SwarmJournal.SwarmJournalEntry inSignal(ObjectMapper mapper, String routingKey, ControlSignal signal) {
    return fromSignal(mapper, SwarmJournal.Direction.IN, routingKey, signal);
  }

  public static SwarmJournal.SwarmJournalEntry outSignal(ObjectMapper mapper, String routingKey, ControlSignal signal) {
    return fromSignal(mapper, SwarmJournal.Direction.OUT, routingKey, signal);
  }

  public static SwarmJournal.SwarmJournalEntry outResult(ObjectMapper mapper, String routingKey, CommandResult result) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(result, "result");
    Map<String, Object> raw = asRaw(mapper, result);
    return new SwarmJournal.SwarmJournalEntry(
        result.timestamp(),
        requireSwarmId(result.scope(), "result.scope"),
        result.data().status() == io.pockethive.swarm.model.lifecycle.TerminalStatus.SUCCEEDED ? "INFO" : "ERROR",
        SwarmJournal.Direction.OUT,
        result.kind(),
        result.type(),
        result.origin(),
        result.scope(),
        result.correlationId(),
        result.idempotencyKey(),
        routingKey,
        mapper.convertValue(result.data(), MAP_TYPE),
        raw,
        null);
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

  private static Map<String, Object> asRaw(ObjectMapper mapper, Object value) {
    return mapper.convertValue(value, MAP_TYPE);
  }

  private static String requireSwarmId(ControlScope scope, String label) {
    if (scope == null || scope.swarmId() == null || scope.swarmId().isBlank()) {
      throw new IllegalArgumentException(label + ".swarmId must not be blank");
    }
    return scope.swarmId();
  }
}
