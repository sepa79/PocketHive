package io.pockethive.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;

class OrchestratorPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class)
        .withPropertyValues(
            "pockethive.control-plane.orchestrator.metrics.adapter=DISABLED",
            "pockethive.control-plane.orchestrator.metrics.publish-interval=PT10S",
            "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
            "pockethive.control-plane.orchestrator.images.repository-prefix=",
            "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
            "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
            "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S",
            "pockethive.control-plane.orchestrator.network-proxy-manager.url=http://network-proxy-manager:8080",
            "pockethive.control-plane.orchestrator.network-proxy-manager.http.connect-timeout=PT5S",
            "pockethive.control-plane.orchestrator.network-proxy-manager.http.read-timeout=PT30S");

    @ParameterizedTest
    @ValueSource(strings = {"control-queue-prefix", "status-queue-prefix"})
    void rejectsRemovedQueuePrefixProperties(String key) {
        contextRunner.withPropertyValues("pockethive.control-plane.orchestrator." + key + "=obsolete")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class);
            });
    }

    @Test
    void bindsOrchestratorTreeFromControlPlanePrefix() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.orchestrator.metrics.adapter=DISABLED",
                "pockethive.control-plane.orchestrator.metrics.publish-interval=PT10S",
                "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
                "pockethive.control-plane.orchestrator.images.repository-prefix=",
                "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
                "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.url=http://network-proxy-manager:8080",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.read-timeout=PT30S")
            .run(context -> {
                assertThat(context).hasNotFailed();
                OrchestratorProperties properties = context.getBean(OrchestratorProperties.class);
                assertThat(properties.getMetrics().getAdapter())
                    .isEqualTo(PocketHiveMetricsAdapter.DISABLED);
                assertThat(properties.getMetrics().getPublishInterval())
                    .isEqualTo(Duration.ofSeconds(10));
                assertThat(properties.getMetrics().getClickHouse().configured()).isFalse();
                assertThat(properties.getDocker().getSocketPath()).isEqualTo("/var/run/docker.sock");
                assertThat(properties.getScenarioManager().getUrl())
                    .isEqualTo("http://scenario-manager:8080");
                assertThat(properties.getScenarioManager().getHttp().getConnectTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(5));
                assertThat(properties.getScenarioManager().getHttp().getReadTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(30));
                assertThat(properties.getNetworkProxyManager().getUrl())
                    .isEqualTo("http://network-proxy-manager:8080");
                assertThat(properties.getNetworkProxyManager().getHttp().getConnectTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(5));
                assertThat(properties.getNetworkProxyManager().getHttp().getReadTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(30));
            });
    }

    @Test
    void bindsClickHouseMetricsFromNestedControlPlanePrefix() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.orchestrator.metrics.adapter=CLICKHOUSE",
                "pockethive.control-plane.orchestrator.metrics.publish-interval=PT10S",
                "pockethive.control-plane.orchestrator.metrics.clickhouse.endpoint=http://clickhouse:8123",
                "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
                "pockethive.control-plane.orchestrator.images.repository-prefix=",
                "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
                "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.url=http://network-proxy-manager:8080",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.read-timeout=PT30S")
            .run(context -> {
                assertThat(context).hasNotFailed();
                ClickHouseMetricsSinkProperties clickHouse =
                    context.getBean(OrchestratorProperties.class).getMetrics().getClickHouse();
                assertThat(clickHouse.configured()).isTrue();
                assertThat(clickHouse.getEndpoint()).isEqualTo("http://clickhouse:8123");
                assertThat(clickHouse.getTable()).isEqualTo(ClickHouseMetricsSinkProperties.DEFAULT_TABLE);
                assertThat(clickHouse.getMaxBufferedSamples()).isEqualTo(50_000);
              var defaults = new ClickHouseMetricsSinkProperties();
              defaults.setEndpoint("http://clickhouse:8123");
              assertThat(clickHouse).usingRecursiveComparison().isEqualTo(defaults);
            });
    }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void bindsClickHouseEnvironmentDirectlyWithServiceYaml(boolean environmentFirst) {
    java.util.Map<String, Object> env = java.util.Map.ofEntries(
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_ENDPOINT", "http://env-clickhouse:8123"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_TABLE", "env.events"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_USERNAME", "writer"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_PASSWORD", "test-password"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_CONNECT_TIMEOUT_MS", "123"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_READ_TIMEOUT_MS", "456"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_BATCH_SIZE", "7"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_FLUSH_INTERVAL_MS", "89"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_MAX_BUFFERED_SAMPLES", "999"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_MAX_LABEL_COUNT", "2"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_MAX_LABEL_KEY_LENGTH", "12"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_METRICS_CLICKHOUSE_MAX_LABEL_VALUE_LENGTH", "34"));
    contextRunner
        .withPropertyValues("pockethive.control-plane.orchestrator.metrics.clickhouse.endpoint=http://property-clickhouse:8123")
        .withPropertyValues("pockethive.control-plane.orchestrator.metrics.adapter=CLICKHOUSE")
        .withInitializer(context -> {
          var environmentSource = new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", env);
          if (environmentFirst) {
            context.getEnvironment().getPropertySources().addFirst(environmentSource);
          } else {
            context.getEnvironment().getPropertySources().replace("systemEnvironment", environmentSource);
          }
          try {
            var yaml = new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "serviceSettings", new org.springframework.core.io.ClassPathResource("application.yml"));
            yaml.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
          } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
          }
        })
        .run(context -> {
          assertThat(context).hasNotFailed();
          var settings = context.getBean(OrchestratorProperties.class).getMetrics().getClickHouse();
          assertThat(settings.getEndpoint()).isEqualTo(environmentFirst
              ? "http://env-clickhouse:8123" : "http://property-clickhouse:8123");
          assertThat(settings.getTable()).isEqualTo("env.events");
          assertThat(settings.getConnectTimeoutMs()).isEqualTo(123);
          assertThat(settings.getBatchSize()).isEqualTo(7);
          assertThat(settings.getMaxBufferedSamples()).isEqualTo(999);
          assertThat(settings.getUsername()).isEqualTo("writer");
          assertThat(settings.getPassword()).isEqualTo("test-password");
          assertThat(settings.getReadTimeoutMs()).isEqualTo(456);
          assertThat(settings.getFlushIntervalMs()).isEqualTo(89);
          assertThat(settings.getMaxLabelCount()).isEqualTo(2);
          assertThat(settings.getMaxLabelKeyLength()).isEqualTo(12);
          assertThat(settings.getMaxLabelValueLength()).isEqualTo(34);
        });
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"", "clickhouse.endpoint=http://clickhouse:8123,clickhouse.batch-size=0"})
  void rejectsMissingEndpointAndInvalidMetricsSettings(String settings) {
    var runner = contextRunner.withPropertyValues(
            "pockethive.control-plane.orchestrator.metrics.adapter=CLICKHOUSE");
    for (String setting : settings.split(",")) {
      if (!setting.isEmpty()) {
        runner = runner.withPropertyValues("pockethive.control-plane.orchestrator.metrics." + setting);
      }
    }
    runner.run(context -> assertThat(context).hasFailed());
  }

    @EnableConfigurationProperties(OrchestratorProperties.class)
    static class TestConfiguration {
        // registers OrchestratorProperties for ApplicationContextRunner
    }
}
