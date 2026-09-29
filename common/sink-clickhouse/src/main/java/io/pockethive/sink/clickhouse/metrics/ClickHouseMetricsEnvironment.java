package io.pockethive.sink.clickhouse.metrics;

import java.util.Map;

/**
 * Responsibility: export metrics sink settings for runtime and controller inheritance.
 * Must not: add defaults, validate properties again or select a metrics adapter.
 * Contract: RESP-CLICKHOUSE-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-environment; configured entries overwrite, blank credentials are omitted.
 */
public final class ClickHouseMetricsEnvironment {
  private static final String RUNTIME_PREFIX = "POCKETHIVE_METRICS_CLICKHOUSE_";
  private static final String CONTROLLER_PREFIX = "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_";

  private ClickHouseMetricsEnvironment() { }

  public static void applyRuntime(Map<String, String> environment, ClickHouseMetricsSinkProperties properties) {
    apply(environment, RUNTIME_PREFIX, properties);
  }

  public static void applyController(Map<String, String> environment, ClickHouseMetricsSinkProperties properties) {
    apply(environment, CONTROLLER_PREFIX, properties);
  }

  private static void apply(Map<String, String> env, String prefix, ClickHouseMetricsSinkProperties properties) {
    if (!properties.configured()) {
      return;
    }
    env.put(prefix + "ENDPOINT", properties.getEndpoint());
    env.put(prefix + "TABLE", properties.getTable());
    putIfNotBlank(env, prefix + "USERNAME", properties.getUsername());
    putIfNotBlank(env, prefix + "PASSWORD", properties.getPassword());
    env.put(prefix + "CONNECT_TIMEOUT_MS", Integer.toString(properties.getConnectTimeoutMs()));
    env.put(prefix + "READ_TIMEOUT_MS", Integer.toString(properties.getReadTimeoutMs()));
    env.put(prefix + "BATCH_SIZE", Integer.toString(properties.getBatchSize()));
    env.put(prefix + "FLUSH_INTERVAL_MS", Integer.toString(properties.getFlushIntervalMs()));
    env.put(prefix + "MAX_BUFFERED_SAMPLES", Integer.toString(properties.getMaxBufferedSamples()));
    env.put(prefix + "MAX_LABEL_COUNT", Integer.toString(properties.getMaxLabelCount()));
    env.put(prefix + "MAX_LABEL_KEY_LENGTH", Integer.toString(properties.getMaxLabelKeyLength()));
    env.put(prefix + "MAX_LABEL_VALUE_LENGTH", Integer.toString(properties.getMaxLabelValueLength()));
  }

  private static void putIfNotBlank(Map<String, String> env, String key, String value) {
    if (value != null && !value.isBlank()) {
      env.put(key, value);
    }
  }
}
