package io.pockethive.sink.clickhouse.metrics;

public class ClickHouseMetricsBufferFullException extends IllegalStateException {

  public ClickHouseMetricsBufferFullException(String message) {
    super(message);
  }
}
