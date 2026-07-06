package io.pockethive.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSample;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSampleSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseMetricsAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ClickHouseMetricsAutoConfiguration.class));

  @Test
  void doesNotCreatePublisherWithoutExplicitClickHouseAdapter() {
    contextRunner
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(context -> {
          assertThat(context).doesNotHaveBean(MicrometerClickHouseMetricsPublisher.class);
          assertThat(context).doesNotHaveBean(ClickHouseMetricsLifecycle.class);
        });
  }

  @Test
  void failsWhenClickHouseAdapterIsEnabledWithoutSinkEndpoint() {
    contextRunner
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(
            "pockethive.metrics.adapter=CLICKHOUSE",
            "pockethive.metrics.publish-interval=PT10S",
            "pockethive.metrics.swarm-id=swarm-a",
            "pockethive.metrics.run-id=run-a",
            "pockethive.metrics.role=processor",
            "pockethive.metrics.instance=processor-1")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void createsPublisherWhenClickHouseAdapterAndSinkAreExplicit() {
    contextRunner
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(ClickHouseMetricSampleSink.class, RecordingSink::new)
        .withPropertyValues(
            "pockethive.metrics.adapter=CLICKHOUSE",
            "pockethive.metrics.publish-interval=PT10S",
            "pockethive.metrics.swarm-id=swarm-a",
            "pockethive.metrics.run-id=run-a",
            "pockethive.metrics.role=processor",
            "pockethive.metrics.instance=processor-1")
        .run(context -> {
          assertThat(context).hasSingleBean(MicrometerClickHouseMetricsPublisher.class);
          assertThat(context).hasSingleBean(ClickHouseMetricsLifecycle.class);
        });
  }

  private static final class RecordingSink implements ClickHouseMetricSampleSink {
    @Override
    public void write(ClickHouseMetricSample sample) {
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }
}
