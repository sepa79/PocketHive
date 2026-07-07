package io.pockethive.sink.clickhouse.metrics;

public class ClickHouseMetricSampleRejectedException extends IllegalArgumentException {

  public ClickHouseMetricSampleRejectedException(String message) {
    super(message);
  }
}
