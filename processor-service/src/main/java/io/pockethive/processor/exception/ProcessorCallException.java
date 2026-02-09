package io.pockethive.processor.exception;

import io.pockethive.processor.metrics.CallMetrics;
import java.util.Map;

public class ProcessorCallException extends Exception {
  private final CallMetrics metrics;
  private final Map<String, Object> request;

  public ProcessorCallException(CallMetrics metrics, Exception cause) {
    this(metrics, cause, Map.of());
  }

  public ProcessorCallException(CallMetrics metrics, Exception cause, Map<String, Object> request) {
    super(cause);
    this.metrics = metrics;
    this.request = request == null ? Map.of() : Map.copyOf(request);
  }

  public CallMetrics metrics() {
    return metrics;
  }

  public Map<String, Object> request() {
    return request;
  }
}
