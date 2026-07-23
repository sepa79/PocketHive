package io.pockethive.swarm.model.lifecycle;

/** Canonical request body for a swarm lifecycle action. */
public record ControlRequest(String idempotencyKey, String notes) {

  public ControlRequest {
    idempotencyKey = ContractValues.requireText("idempotencyKey", idempotencyKey);
    notes = ContractValues.optionalText(notes);
  }
}
