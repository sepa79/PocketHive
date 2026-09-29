package io.pockethive.acceptance.api;

import io.pockethive.swarm.model.lifecycle.ControlResponse;

/**
 * Responsibility: report request/receipt identity mismatch while retaining the accepted operation link.
 * Must not: treat the mismatch as success or decide resource ownership/cleanup.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class ControlReceiptMismatchException extends AssertionError {
  private final ControlResponse receipt;
  public ControlReceiptMismatchException(String requestedKey, ControlResponse receipt) {
    this(receipt, "Control response idempotencyKey mismatch: requested " + requestedKey
        + ", received " + receipt.idempotencyKey());
  }
  public static ControlReceiptMismatchException forDispatch(String expectedTarget, ControlResponse receipt) {
    return new ControlReceiptMismatchException(receipt, "Control dispatch target mismatch: expected " + expectedTarget);
  }
  private ControlReceiptMismatchException(ControlResponse receipt, String description) {
    super(description + "; operation " + receipt.correlationId());
    this.receipt = receipt;
  }
  public ControlResponse receipt() { return receipt; }
}
