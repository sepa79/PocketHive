package io.pockethive.control;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Correlated non-terminal operational evidence that must never complete an operation. */
public record JournalEvent(
    Instant timestamp,
    String version,
    String kind,
    String type,
    String origin,
    ControlScope scope,
    String correlationId,
    String idempotencyKey,
    Map<String, Object> runtime,
    Map<String, Object> data
) {

  public static final String KIND = "journal";

  public JournalEvent {
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    version = CommandEnvelopeSupport.requireCurrentVersion(version);
    kind = CommandEnvelopeSupport.requireKind(KIND, kind);
    type = CommandEnvelopeSupport.requireText("type", type);
    origin = CommandEnvelopeSupport.requireText("origin", origin);
    scope = Objects.requireNonNull(scope, "scope");
    correlationId = CommandEnvelopeSupport.requireText("correlationId", correlationId);
    idempotencyKey = CommandEnvelopeSupport.requireText("idempotencyKey", idempotencyKey);
    runtime = ControlRuntime.normalise(runtime);
    if (data == null || data.isEmpty()) {
      throw new IllegalArgumentException("data must not be empty");
    }
    data = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
