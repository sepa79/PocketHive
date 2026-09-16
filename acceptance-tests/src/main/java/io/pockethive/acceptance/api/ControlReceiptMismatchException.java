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
    super("Control response idempotencyKey mismatch: requested " + requestedKey
        + ", received " + receipt.idempotencyKey() + "; operation " + receipt.correlationId());
    this.receipt = receipt;
  }
  public ControlResponse receipt() { return receipt; }
}
