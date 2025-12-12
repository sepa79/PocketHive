package io.pockethive.processor.metrics;

public record CallMetrics(long durationMs, long connectionLatencyMs, boolean success, int statusCode) {
  public static CallMetrics success(long durationMs, long connectionLatencyMs, int statusCode) {
    return new CallMetrics(durationMs, connectionLatencyMs, true, statusCode);
  }

  public static CallMetrics failure(long durationMs, long connectionLatencyMs, int statusCode) {
    return new CallMetrics(durationMs, connectionLatencyMs, false, statusCode);
  }
}
