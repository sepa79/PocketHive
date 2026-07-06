package io.pockethive.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricKind;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSample;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSampleRejectedException;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSampleSink;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricStatistic;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrometerClickHouseMetricsPublisherTest {

  private static final Instant EVENT_TIME = Instant.parse("2026-07-03T11:00:00Z");

  @Test
  void publishesCounterGaugeAndSummaryMeasurements() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Counter.builder("ph_errors_total")
        .tag("queue", "final-out")
        .register(registry)
        .increment(3.0);
    AtomicReference<Double> gaugeValue = new AtomicReference<>(12.5);
    Gauge.builder("ph_swarm_queue_depth", gaugeValue, AtomicReference::get)
        .tag("queue", "moderator-a-out")
        .baseUnit("messages")
        .register(registry);
    DistributionSummary summary = DistributionSummary.builder("ph_total_latency_ms")
        .baseUnit("milliseconds")
        .register(registry);
    summary.record(10.0);
    summary.record(30.0);
    Timer timer = Timer.builder("pockethive.worker.invocation.duration").register(registry);
    timer.record(Duration.ofMillis(250));
    RecordingSink sink = new RecordingSink();

    MicrometerClickHouseMetricsPublisher.PublishResult result =
        publisher(registry, sink, properties()).publishOnce();

    assertEquals(4, result.meters());
    assertEquals(8, result.samples());
    assertEquals(0, result.skippedMeasurements());
    assertEquals(0, result.rejectedSamples());
    assertEquals(1, sink.flushes);

    ClickHouseMetricSample counter = onlySample(sink.samples, "ph_errors_total", ClickHouseMetricStatistic.COUNT);
    assertEquals(EVENT_TIME, counter.eventTime());
    assertEquals("swarm-a", counter.swarmId());
    assertEquals("run-a", counter.runId());
    assertEquals("processor", counter.role());
    assertEquals("processor-1", counter.instance());
    assertEquals(ClickHouseMetricKind.COUNTER, counter.metricKind());
    assertEquals(3.0, counter.value());
    assertEquals("count", counter.unit());
    assertEquals("final-out", counter.labels().get("queue"));

    ClickHouseMetricSample gauge = onlySample(sink.samples, "ph_swarm_queue_depth", ClickHouseMetricStatistic.VALUE);
    assertEquals(ClickHouseMetricKind.GAUGE, gauge.metricKind());
    assertEquals(12.5, gauge.value());
    assertEquals("messages", gauge.unit());

    ClickHouseMetricSample summaryCount =
        onlySample(sink.samples, "ph_total_latency_ms", ClickHouseMetricStatistic.COUNT);
    assertEquals(ClickHouseMetricKind.TIMER, summaryCount.metricKind());
    assertEquals(2.0, summaryCount.value());
    assertEquals("milliseconds", summaryCount.unit());

    ClickHouseMetricSample summaryTotal =
        onlySample(sink.samples, "ph_total_latency_ms", ClickHouseMetricStatistic.SUM);
    assertEquals(40.0, summaryTotal.value());

    ClickHouseMetricSample timerTotal =
        onlySample(sink.samples, "pockethive.worker.invocation.duration", ClickHouseMetricStatistic.SUM);
    assertEquals(ClickHouseMetricKind.TIMER, timerTotal.metricKind());
    assertEquals("seconds", timerTotal.unit());
  }

  @Test
  void skipsNonFiniteGaugeValues() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicReference<Double> gaugeValue = new AtomicReference<>(Double.NaN);
    Gauge.builder("ph_nan_gauge", gaugeValue, AtomicReference::get).register(registry);
    RecordingSink sink = new RecordingSink();

    MicrometerClickHouseMetricsPublisher.PublishResult result =
        publisher(registry, sink, properties()).publishOnce();

    assertEquals(1, result.meters());
    assertEquals(0, result.samples());
    assertTrue(result.skippedMeasurements() > 0);
    assertEquals(0, result.rejectedSamples());
    assertTrue(sink.samples.isEmpty());
    assertEquals(1, sink.flushes);
  }

  @Test
  void skipsRejectedSampleAndContinuesPublishingLaterMeters() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Counter.builder("ph_bad_total").register(registry).increment(1.0);
    Counter.builder("ph_good_total").register(registry).increment(2.0);
    RejectingSink sink = new RejectingSink();

    MicrometerClickHouseMetricsPublisher.PublishResult result =
        publisher(registry, sink, properties()).publishOnce();

    assertEquals(2, result.meters());
    assertEquals(1, result.samples());
    assertEquals(1, result.skippedMeasurements());
    assertEquals(1, result.rejectedSamples());
    assertEquals(2, sink.attemptedMetricNames.size());
    assertTrue(sink.attemptedMetricNames.containsAll(List.of("ph_bad_total", "ph_good_total")));
    assertEquals(1, sink.flushes);
    assertEquals(1, sink.samples.size());
    assertEquals(sink.attemptedMetricNames.get(1), sink.samples.getFirst().metricName());
  }

  @Test
  void propagatesTransportFailuresWithoutFlushingCycle() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Counter.builder("ph_first_total").register(registry).increment(1.0);
    Counter.builder("ph_second_total").register(registry).increment(2.0);
    TransportFailingSink sink = new TransportFailingSink();

    IOException ex = assertThrows(IOException.class, () -> publisher(registry, sink, properties()).publishOnce());

    assertTrue(ex.getMessage().contains("clickhouse down"));
    assertEquals(1, sink.writes);
    assertEquals(0, sink.flushes);
  }

  @Test
  void requiresExplicitClickHouseAdapterProperties() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RecordingSink sink = new RecordingSink();
    PocketHiveMetricsProperties properties = properties();
    properties.setRunId("");

    MicrometerClickHouseMetricsPublisher publisher = publisher(registry, sink, properties);

    IllegalStateException ex = assertThrows(IllegalStateException.class, publisher::publishOnce);
    assertTrue(ex.getMessage().contains("run-id"));
  }

  private static MicrometerClickHouseMetricsPublisher publisher(
      SimpleMeterRegistry registry,
      RecordingSink sink,
      PocketHiveMetricsProperties properties) {
    return new MicrometerClickHouseMetricsPublisher(
        registry,
        sink,
        properties,
        Clock.fixed(EVENT_TIME, ZoneOffset.UTC));
  }

  private static PocketHiveMetricsProperties properties() {
    PocketHiveMetricsProperties properties = new PocketHiveMetricsProperties();
    properties.setAdapter(PocketHiveMetricsAdapter.CLICKHOUSE);
    properties.setPublishInterval(Duration.ofSeconds(10));
    properties.setSwarmId("swarm-a");
    properties.setRunId("run-a");
    properties.setRole("processor");
    properties.setInstance("processor-1");
    return properties;
  }

  private static ClickHouseMetricSample onlySample(
      List<ClickHouseMetricSample> samples,
      String metricName,
      ClickHouseMetricStatistic statistic) {
    List<ClickHouseMetricSample> matches = samples.stream()
        .filter(sample -> sample.metricName().equals(metricName))
        .filter(sample -> sample.statistic() == statistic)
        .toList();
    assertFalse(matches.isEmpty(), () -> "No sample for " + metricName + " " + statistic);
    assertEquals(1, matches.size(), () -> "Expected one sample for " + metricName + " " + statistic);
    return matches.getFirst();
  }

  private static class RecordingSink implements ClickHouseMetricSampleSink {
    final List<ClickHouseMetricSample> samples = new ArrayList<>();
    int flushes;

    @Override
    public void write(ClickHouseMetricSample sample) throws Exception {
      samples.add(sample);
    }

    @Override
    public void flush() {
      flushes++;
    }

    @Override
    public void close() {
    }
  }

  private static final class RejectingSink extends RecordingSink {
    private final List<String> attemptedMetricNames = new ArrayList<>();
    private boolean rejected;

    @Override
    public void write(ClickHouseMetricSample sample) throws Exception {
      attemptedMetricNames.add(sample.metricName());
      if (!rejected) {
        rejected = true;
        throw new ClickHouseMetricSampleRejectedException("test rejected sample");
      }
      super.write(sample);
    }
  }

  private static final class TransportFailingSink extends RecordingSink {
    private int writes;

    @Override
    public void write(ClickHouseMetricSample sample) throws IOException {
      writes++;
      throw new IOException("clickhouse down");
    }
  }
}
