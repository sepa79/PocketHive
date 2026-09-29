package io.pockethive.sink.clickhouse.metrics;

import java.util.Map;

/**
 * Responsibility: represent a serialized metrics row, projected from a metric sample.
 * Must not: hold independent state or perform transport/configuration decisions.
 * Contract: RESP-CLICKHOUSE-INSERT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-insert.
 */
record ClickHouseMetricRow(
    String eventTime,
    String swarmId,
    String runId,
    String role,
    String instance,
    String metricName,
    String metricKind,
    String statistic,
    double value,
    String unit,
    Map<String, String> labels) {
}
