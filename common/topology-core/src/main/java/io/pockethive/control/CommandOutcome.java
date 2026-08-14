package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Public terminal command envelope emitted only by the Orchestrator. */
public record CommandOutcome(
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

  public static final String KIND = "outcome";
  public static final String OWNER_ROLE = "orchestrator";

  public CommandOutcome {
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    version = CommandEnvelopeSupport.requireCurrentVersion(version);
    kind = CommandEnvelopeSupport.requireKind(KIND, kind);
    type = CommandEnvelopeSupport.requireText("type", type);
    origin = CommandEnvelopeSupport.requireText("origin", origin);
    scope = Objects.requireNonNull(scope, "scope");
    if (!OWNER_ROLE.equals(scope.role())) {
      throw new IllegalArgumentException("Public outcome scope role must be orchestrator");
    }
    correlationId = CommandEnvelopeSupport.requireText("correlationId", correlationId);
    idempotencyKey = CommandEnvelopeSupport.requireText("idempotencyKey", idempotencyKey);
    runtime = ControlRuntime.normalise(scope, runtime);
    data = Objects.requireNonNull(data, "data");
  }

  public static CommandOutcome create(
      String type,
      String origin,
      ControlScope scope,
      String correlationId,
      String idempotencyKey,
      Map<String, Object> runtime,
      TerminalResult data) {
    return new CommandOutcome(
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
