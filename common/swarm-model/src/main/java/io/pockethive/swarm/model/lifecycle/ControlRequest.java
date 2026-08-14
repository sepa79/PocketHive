package io.pockethive.swarm.model.lifecycle;

/** Canonical request body for a swarm lifecycle action. */
public record ControlRequest(String idempotencyKey) {

  public ControlRequest {
    idempotencyKey = ContractValues.requireText("idempotencyKey", idempotencyKey);
  }
}
