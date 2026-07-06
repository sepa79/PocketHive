package io.pockethive.sink.clickhouse.metrics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClickHouseMetricSampleTest {

  @Test
  void normalizesAndFreezesLabels() {
    ClickHouseMetricSample sample = sample(Map.of("z", "last", "a", "first"));

    assertThat(sample.labels()).containsExactly(
        Map.entry("a", "first"),
        Map.entry("z", "last"));
    assertThatThrownBy(() -> sample.labels().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requiresExplicitDimensionsAndFiniteValue() {
    assertThatThrownBy(() -> new ClickHouseMetricSample(
        Instant.parse("2026-07-03T10:15:30Z"),
        "swarm-a",
        "",
        "processor",
        "processor-1",
        "ph_test_total",
        ClickHouseMetricKind.COUNTER,
        ClickHouseMetricStatistic.VALUE,
        1.0,
        "count",
        Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId");

    assertThatThrownBy(() -> new ClickHouseMetricSample(
        Instant.parse("2026-07-03T10:15:30Z"),
        "swarm-a",
        ClickHouseMetricSample.NOT_RUN_SCOPED,
        "processor",
        "processor-1",
        "ph_test_total",
        ClickHouseMetricKind.COUNTER,
        ClickHouseMetricStatistic.VALUE,
        Double.NaN,
        "count",
        Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finite");
  }

  @Test
  void rejectsDuplicateLabelKeysAfterTrimming() {
    LinkedHashMap<String, String> labels = new LinkedHashMap<>();
    labels.put("queue", "one");
    labels.put(" queue ", "two");

    assertThatThrownBy(() -> sample(labels))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
  }

  private static ClickHouseMetricSample sample(Map<String, String> labels) {
    return new ClickHouseMetricSample(
        Instant.parse("2026-07-03T10:15:30.123Z"),
        "swarm-a",
        ClickHouseMetricSample.NOT_RUN_SCOPED,
        "processor",
        "processor-1",
        "ph_test_total",
        ClickHouseMetricKind.COUNTER,
        ClickHouseMetricStatistic.VALUE,
        1.0,
        "count",
        labels);
  }
}
