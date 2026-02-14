package io.pockethive.postprocessor;

/**
 * Signals that the tx-outcome ClickHouse writer buffer is full and can no longer accept events.
 */
final class ClickHouseTxOutcomeBufferFullException extends IllegalStateException {

  ClickHouseTxOutcomeBufferFullException(String message) {
    super(message);
  }
}

