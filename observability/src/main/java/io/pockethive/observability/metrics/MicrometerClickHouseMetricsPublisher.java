package io.pockethive.observability.metrics;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricKind;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSample;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSampleRejectedException;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSampleSink;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricStatistic;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsBufferFullException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MicrometerClickHouseMetricsPublisher {

  private static final Logger log = LoggerFactory.getLogger(MicrometerClickHouseMetricsPublisher.class);

  private final MeterRegistry meterRegistry;
  private final ClickHouseMetricSampleSink sink;
  private final PocketHiveMetricsProperties properties;
  private final Clock clock;

  public MicrometerClickHouseMetricsPublisher(
      MeterRegistry meterRegistry,
      ClickHouseMetricSampleSink sink,
      PocketHiveMetricsProperties properties) {
    this(meterRegistry, sink, properties, Clock.systemUTC());
  }

  MicrometerClickHouseMetricsPublisher(
      MeterRegistry meterRegistry,
      ClickHouseMetricSampleSink sink,
      PocketHiveMetricsProperties properties,
      Clock clock) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public PublishResult publishOnce() throws Exception {
    properties.requireClickHouseAdapter();
    Instant eventTime = clock.instant();
    int meters = 0;
    int samples = 0;
    int skipped = 0;
    int rejected = 0;
    for (Meter meter : meterRegistry.getMeters()) {
      meters++;
      for (Measurement measurement : meter.measure()) {
        Optional<ClickHouseMetricSample> sample;
        try {
          sample = sampleFor(meter, measurement, eventTime);
        } catch (IllegalArgumentException ex) {
          skipped++;
          rejected++;
          log.warn(
              "Rejected ClickHouse metric measurement metric={} statistic={} reason={}",
              meter.getId().getName(),
              measurement.getStatistic(),
              ex.getMessage());
          continue;
        }
        if (sample.isPresent()) {
          ClickHouseMetricSample resolved = sample.orElseThrow();
          try {
            sink.write(resolved);
            samples++;
          } catch (ClickHouseMetricSampleRejectedException | ClickHouseMetricsBufferFullException ex) {
            skipped++;
            rejected++;
            log.warn(
                "Rejected ClickHouse metric sample metric={} statistic={} reason={}",
                resolved.metricName(),
                resolved.statistic(),
                ex.getMessage());
          }
        } else {
          skipped++;
        }
      }
    }
    sink.flush();
    return new PublishResult(meters, samples, skipped, rejected);
  }

  private Optional<ClickHouseMetricSample> sampleFor(
      Meter meter,
      Measurement measurement,
      Instant eventTime) {
    double value = measurement.getValue();
    if (!Double.isFinite(value)) {
      return Optional.empty();
    }
    ClickHouseMetricStatistic statistic = statistic(measurement.getStatistic().name()).orElse(null);
    if (statistic == null) {
      return Optional.empty();
    }
    return Optional.of(new ClickHouseMetricSample(
        eventTime,
        properties.requiredSwarmId(),
        properties.requiredRunId(),
        properties.requiredRole(),
        properties.requiredInstance(),
        meter.getId().getName(),
        kind(meter.getId().getType()),
        statistic,
        value,
        unit(meter.getId(), measurement),
        labels(meter.getId())));
  }

  private static ClickHouseMetricKind kind(Meter.Type type) {
    return switch (type) {
      case COUNTER -> ClickHouseMetricKind.COUNTER;
      case TIMER, LONG_TASK_TIMER, DISTRIBUTION_SUMMARY -> ClickHouseMetricKind.TIMER;
      case GAUGE -> ClickHouseMetricKind.GAUGE;
      case OTHER -> ClickHouseMetricKind.GAUGE;
    };
  }

  private static Optional<ClickHouseMetricStatistic> statistic(String statistic) {
    return switch (statistic) {
      case "VALUE", "ACTIVE_TASKS" -> Optional.of(ClickHouseMetricStatistic.VALUE);
      case "COUNT" -> Optional.of(ClickHouseMetricStatistic.COUNT);
      case "TOTAL", "TOTAL_TIME", "DURATION" -> Optional.of(ClickHouseMetricStatistic.SUM);
      case "MAX" -> Optional.of(ClickHouseMetricStatistic.MAX);
      default -> Optional.empty();
    };
  }

  private static String unit(Meter.Id id, Measurement measurement) {
    String baseUnit = id.getBaseUnit();
    if (baseUnit != null && !baseUnit.isBlank()) {
      return baseUnit.trim();
    }
    String statistic = measurement.getStatistic().name();
    if ("COUNT".equals(statistic) || id.getType() == Meter.Type.COUNTER) {
      return "count";
    }
    if (id.getType() == Meter.Type.TIMER || id.getType() == Meter.Type.LONG_TASK_TIMER) {
      return "seconds";
    }
    return ClickHouseMetricSample.NO_UNIT;
  }

  private static Map<String, String> labels(Meter.Id id) {
    Map<String, String> labels = new LinkedHashMap<>();
    for (Tag tag : id.getTags()) {
      labels.put(tag.getKey(), tag.getValue());
    }
    return labels;
  }

  public record PublishResult(int meters, int samples, int skippedMeasurements, int rejectedSamples) {
  }
}
