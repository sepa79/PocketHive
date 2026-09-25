package io.pockethive.acceptance.api;

import io.pockethive.swarm.model.lifecycle.ControlResponse;

/**
 * Responsibility: match canonical acknowledgements to their submitted idempotency key.
 * Must not: decide operation success, synthesize receipts or retry commands.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
public final class ControlReceipts {
  private ControlReceipts() {}
  public static ControlResponse requireKey(ControlResponse receipt, String requestedKey) {
    if (!requestedKey.equals(receipt.idempotencyKey())) throw new ControlReceiptMismatchException(requestedKey, receipt);
    return receipt;
  }
}
