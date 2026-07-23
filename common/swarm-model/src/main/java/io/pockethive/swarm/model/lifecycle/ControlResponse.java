package io.pockethive.swarm.model.lifecycle;

/** Canonical REST acknowledgement for an accepted asynchronous control operation. */
public record ControlResponse(
    String correlationId,
    String idempotencyKey,
    String operationUrl,
    String outcomeTopic,
    long timeoutMs
) {

  public ControlResponse {
    correlationId = ContractValues.requireText("correlationId", correlationId);
    idempotencyKey = ContractValues.requireText("idempotencyKey", idempotencyKey);
    operationUrl = ContractValues.requireText("operationUrl", operationUrl);
    outcomeTopic = ContractValues.requireText("outcomeTopic", outcomeTopic);
    if (timeoutMs <= 0) {
      throw new IllegalArgumentException("timeoutMs must be positive");
    }
  }
}
