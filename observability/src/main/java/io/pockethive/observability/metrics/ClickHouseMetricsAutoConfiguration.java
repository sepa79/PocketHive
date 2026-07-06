package io.pockethive.observability.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricSampleSink;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSink;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {
    MetricsAutoConfiguration.class,
    CompositeMeterRegistryAutoConfiguration.class,
    SimpleMetricsExportAutoConfiguration.class,
    PrometheusMetricsExportAutoConfiguration.class
})
@ConditionalOnClass({MeterRegistry.class, ClickHouseMetricsSink.class})
@ConditionalOnProperty(prefix = "pockethive.metrics", name = "adapter", havingValue = "CLICKHOUSE")
@EnableConfigurationProperties({
    PocketHiveMetricsProperties.class,
    ClickHouseMetricsSinkProperties.class
})
public class ClickHouseMetricsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ClickHouseMetricSampleSink clickHouseMetricSampleSink(
      ClickHouseMetricsSinkProperties properties,
      ObjectProvider<ObjectMapper> objectMapperProvider) {
    properties.requireConfigured();
    return new ClickHouseMetricsSink(properties, objectMapperProvider.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnMissingBean
  MicrometerClickHouseMetricsPublisher micrometerClickHouseMetricsPublisher(
      MeterRegistry meterRegistry,
      ClickHouseMetricSampleSink sink,
      PocketHiveMetricsProperties properties) {
    properties.requireClickHouseAdapter();
    return new MicrometerClickHouseMetricsPublisher(meterRegistry, sink, properties);
  }

  @Bean
  @ConditionalOnBean(MicrometerClickHouseMetricsPublisher.class)
  @ConditionalOnMissingBean
  ClickHouseMetricsLifecycle clickHouseMetricsLifecycle(
      PocketHiveMetricsProperties properties,
      MicrometerClickHouseMetricsPublisher publisher) {
    return new ClickHouseMetricsLifecycle(properties, publisher);
  }
}
