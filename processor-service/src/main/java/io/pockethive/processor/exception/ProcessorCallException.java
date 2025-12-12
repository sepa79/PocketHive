package io.pockethive.processor.exception;

import io.pockethive.processor.metrics.CallMetrics;

public class ProcessorCallException extends Exception {
  private final CallMetrics metrics;

  public ProcessorCallException(CallMetrics metrics, Exception cause) {
    super(cause);
    this.metrics = metrics;
  }

  public CallMetrics metrics() {
    return metrics;
  }
}
