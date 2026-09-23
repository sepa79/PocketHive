package io.pockethive.sink.clickhouse;

import java.util.Map;

/**
 * Responsibility: export transaction sink properties to missing launch environment entries.
 * Must not: override present keys, add defaults or decide whether a worker uses this sink.
 * Contract: RESP-CLICKHOUSE-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-environment.
 */
public final class ClickHouseSinkEnvironment {
  public static final String ENDPOINT = "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT";
  public static final String TABLE = "POCKETHIVE_SINK_CLICKHOUSE_TABLE";
  public static final String USERNAME = "POCKETHIVE_SINK_CLICKHOUSE_USERNAME";
  public static final String PASSWORD = "POCKETHIVE_SINK_CLICKHOUSE_PASSWORD";
  public static final String CONNECT_TIMEOUT_MS = "POCKETHIVE_SINK_CLICKHOUSE_CONNECT_TIMEOUT_MS";
  public static final String READ_TIMEOUT_MS = "POCKETHIVE_SINK_CLICKHOUSE_READ_TIMEOUT_MS";
  public static final String BATCH_SIZE = "POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE";
  public static final String FLUSH_INTERVAL_MS = "POCKETHIVE_SINK_CLICKHOUSE_FLUSH_INTERVAL_MS";
  public static final String MAX_BUFFERED_EVENTS = "POCKETHIVE_SINK_CLICKHOUSE_MAX_BUFFERED_EVENTS";

  private ClickHouseSinkEnvironment() { }

  public static void applyMissing(Map<String, String> environment, ClickHouseSinkProperties properties) {
    if (!properties.configured()) {
      return;
    }
    putMissing(environment, ENDPOINT, properties.getEndpoint());
    putMissing(environment, TABLE, properties.getTable());
    putMissing(environment, USERNAME, properties.getUsername());
    putMissing(environment, PASSWORD, properties.getPassword());
    putMissing(environment, CONNECT_TIMEOUT_MS, Integer.toString(properties.getConnectTimeoutMs()));
    putMissing(environment, READ_TIMEOUT_MS, Integer.toString(properties.getReadTimeoutMs()));
    putMissing(environment, BATCH_SIZE, Integer.toString(properties.getBatchSize()));
    putMissing(environment, FLUSH_INTERVAL_MS, Integer.toString(properties.getFlushIntervalMs()));
    putMissing(environment, MAX_BUFFERED_EVENTS, Integer.toString(properties.getMaxBufferedEvents()));
  }

  private static void putMissing(Map<String, String> environment, String key, String value) {
    if (environment.containsKey(key) || value == null) {
      return;
    }
    String text = value.trim();
    if (!text.isBlank()) {
      environment.put(key, text);
    }
  }
}
