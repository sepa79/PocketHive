package io.pockethive.functionalswarm.contracts;

import io.pockethive.worker.sdk.api.HttpResultEnvelope;
import java.util.Objects;

/** The sole mapper from Processor's HTTP-result contract to the Functional Swarm result. */
public final class FunctionalSwarmResponseMapper {
  private FunctionalSwarmResponseMapper() {
  }

  public static FunctionalSwarmResponse fromHttpResult(HttpResultEnvelope result) {
    Objects.requireNonNull(result, "result");
    HttpResultEnvelope.HttpOutcome outcome = result.outcome();
    if (!HttpResultEnvelope.OUTCOME_HTTP_RESPONSE.equals(outcome.type())) {
      throw new IllegalArgumentException("Functional Swarm requires an HTTP response outcome");
    }
    return new FunctionalSwarmResponse(outcome.status(), outcome.headers(), outcome.body());
  }
}
