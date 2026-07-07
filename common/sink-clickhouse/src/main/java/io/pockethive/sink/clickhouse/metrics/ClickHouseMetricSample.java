package io.pockethive.sink.clickhouse.metrics;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ClickHouseMetricSample(
    Instant eventTime,
    String swarmId,
    String runId,
    String role,
    String instance,
    String metricName,
    ClickHouseMetricKind metricKind,
    ClickHouseMetricStatistic statistic,
    double value,
    String unit,
    Map<String, String> labels) {

  public static final String NOT_RUN_SCOPED = "not-run-scoped";
  public static final String NO_UNIT = "none";

  public ClickHouseMetricSample {
    Objects.requireNonNull(eventTime, "eventTime");
    swarmId = requireNonBlank(swarmId, "swarmId");
    runId = requireNonBlank(runId, "runId");
    role = requireNonBlank(role, "role");
    instance = requireNonBlank(instance, "instance");
    metricName = requireNonBlank(metricName, "metricName");
    Objects.requireNonNull(metricKind, "metricKind");
    Objects.requireNonNull(statistic, "statistic");
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("value must be finite");
    }
    unit = requireNonBlank(unit, "unit");
    labels = normalizeLabels(labels);
  }

  private static Map<String, String> normalizeLabels(Map<String, String> labels) {
    if (labels == null || labels.isEmpty()) {
      return Map.of();
    }
    TreeMap<String, String> normalized = new TreeMap<>();
    labels.forEach((key, value) -> {
      String normalizedKey = requireNonBlank(key, "label key");
      String previous = normalized.put(normalizedKey, requireNonBlank(value, "label value"));
      if (previous != null) {
        throw new IllegalArgumentException("label key must be unique after trimming: " + normalizedKey);
      }
    });
    return Collections.unmodifiableMap(normalized);
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be null or blank");
    }
    return value.trim();
  }
}
