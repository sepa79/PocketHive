package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Internal terminal evidence emitted by the concrete AMQP command executor. */
public record CommandResult(
    Instant timestamp,
    String version,
    String kind,
    String type,
    String origin,
    ControlScope scope,
    String correlationId,
    String idempotencyKey,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Map<String, Object> runtime,
    TerminalResult data
) implements ControlPlaneEnvelope {

  public static final String KIND = "result";

  public CommandResult {
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    version = CommandEnvelopeSupport.requireCurrentVersion(version);
    kind = CommandEnvelopeSupport.requireKind(KIND, kind);
    type = CommandEnvelopeSupport.requireText("type", type);
    origin = CommandEnvelopeSupport.requireText("origin", origin);
    scope = Objects.requireNonNull(scope, "scope");
    correlationId = CommandEnvelopeSupport.requireText("correlationId", correlationId);
    idempotencyKey = CommandEnvelopeSupport.requireText("idempotencyKey", idempotencyKey);
    runtime = ControlRuntime.normalise(scope, runtime);
    data = Objects.requireNonNull(data, "data");
  }

  public static CommandResult create(
      String type,
      String origin,
      ControlScope scope,
      String correlationId,
      String idempotencyKey,
      Map<String, Object> runtime,
      TerminalResult data) {
    return new CommandResult(
        Instant.now(),
        ControlPlaneEnvelopeVersion.CURRENT,
        KIND,
        type,
        origin,
        scope,
        correlationId,
        idempotencyKey,
        runtime,
        data);
  }
}
