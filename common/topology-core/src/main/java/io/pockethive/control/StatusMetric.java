package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Canonical Java envelope for status-full and status-delta metrics. */
public record StatusMetric(
    Instant timestamp,
    String version,
    String kind,
    String type,
    String origin,
    ControlScope scope,
    String correlationId,
    String idempotencyKey,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> runtime,
    Map<String, Object> data
) implements ControlPlaneEnvelope {

  public static final String KIND = "metric";
  public static final String STATUS_FULL = "status-full";
  public static final String STATUS_DELTA = "status-delta";

  public StatusMetric {
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    version = CommandEnvelopeSupport.requireCurrentVersion(version);
    kind = CommandEnvelopeSupport.requireKind(KIND, kind);
    type = CommandEnvelopeSupport.requireText("type", type);
    if (!STATUS_FULL.equals(type) && !STATUS_DELTA.equals(type)) {
      throw new IllegalArgumentException("Unsupported status metric type: " + type);
    }
    origin = CommandEnvelopeSupport.requireText("origin", origin);
    scope = Objects.requireNonNull(scope, "scope");
    if (correlationId != null || idempotencyKey != null) {
      throw new IllegalArgumentException("Status metrics must not carry operation identity");
    }
    runtime = ControlRuntime.normalise(scope, runtime);
    if (data == null || data.isEmpty()) {
      throw new IllegalArgumentException("data must not be empty");
    }
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
