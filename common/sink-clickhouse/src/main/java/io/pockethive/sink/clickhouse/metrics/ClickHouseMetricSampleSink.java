package io.pockethive.sink.clickhouse.metrics;

public interface ClickHouseMetricSampleSink extends AutoCloseable {

  void write(ClickHouseMetricSample sample) throws Exception;

  void flush() throws Exception;
}
