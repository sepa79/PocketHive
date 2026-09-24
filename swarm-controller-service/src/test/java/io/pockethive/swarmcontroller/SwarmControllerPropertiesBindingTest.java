package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

class SwarmControllerPropertiesBindingTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(Config.class);

  @Test
  void bindsWhenOnlyCoreControlPlanePropertiesProvided() {
    contextRunner
        .withPropertyValues(
            "pockethive.control-plane.swarm-id=swarm-a",
            "pockethive.control-plane.exchange=ph.control",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.control-plane.manager.role=swarm-controller",
            "pockethive.control-plane.swarm-controller.metrics.adapter=DISABLED",
            "pockethive.control-plane.swarm-controller.metrics.publish-interval=PT10S",
            "pockethive.control-plane.swarm-controller.docker.socket-path=/var/run/docker.sock")
        .run(
            context -> {
              SwarmControllerProperties properties =
                  context.getBean(SwarmControllerProperties.class);
              assertThat(properties.getSwarmId()).isEqualTo("swarm-a");
              assertThat(properties.getRole()).isEqualTo("swarm-controller");
              assertThat(properties.getMetrics().adapter())
                  .isEqualTo(PocketHiveMetricsAdapter.DISABLED);
              assertThat(properties.getMetrics().publishInterval())
                  .isEqualTo(Duration.ofSeconds(10));
              assertThat(properties.getMetrics().clickHouse().configured()).isFalse();
              assertThat(properties.getDocker().socketPath()).isEqualTo("/var/run/docker.sock");
            });
  }

  @Test
  void bindsClickHouseMetricsFromNestedControlPlanePrefix() {
    contextRunner
        .withPropertyValues(
            "pockethive.control-plane.swarm-id=swarm-a",
            "pockethive.control-plane.exchange=ph.control",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.control-plane.manager.role=swarm-controller",
            "pockethive.control-plane.swarm-controller.metrics.adapter=CLICKHOUSE",
            "pockethive.control-plane.swarm-controller.metrics.publish-interval=PT10S",
            "pockethive.control-plane.swarm-controller.metrics.clickhouse.endpoint=http://clickhouse:8123",
            "pockethive.control-plane.swarm-controller.docker.socket-path=/var/run/docker.sock")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ClickHouseMetricsSinkProperties clickHouse =
                  context.getBean(SwarmControllerProperties.class).getMetrics().clickHouse();
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
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_ENDPOINT", "http://env-clickhouse:8123"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_TABLE", "env.events"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_USERNAME", "writer"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_PASSWORD", "test-password"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_CONNECT_TIMEOUT_MS", "123"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_READ_TIMEOUT_MS", "456"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_BATCH_SIZE", "7"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_FLUSH_INTERVAL_MS", "89"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_BUFFERED_SAMPLES", "999"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_COUNT", "2"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_KEY_LENGTH", "12"),
        java.util.Map.entry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_VALUE_LENGTH", "34"));
    contextRunner
        .withPropertyValues("pockethive.control-plane.swarm-controller.metrics.clickhouse.endpoint=http://property-clickhouse:8123")
        .withPropertyValues(
            "pockethive.control-plane.swarm-id=swarm-a",
            "pockethive.control-plane.exchange=ph.control",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.control-plane.manager.role=swarm-controller",
            "pockethive.control-plane.swarm-controller.metrics.adapter=CLICKHOUSE",
            "pockethive.control-plane.swarm-controller.metrics.publish-interval=PT10S",
            "pockethive.control-plane.swarm-controller.docker.socket-path=/var/run/docker.sock")
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
          var settings = context.getBean(SwarmControllerProperties.class).getMetrics().clickHouse();
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
            "pockethive.control-plane.swarm-id=swarm-a",
            "pockethive.control-plane.exchange=ph.control",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.control-plane.manager.role=swarm-controller",
            "pockethive.control-plane.swarm-controller.metrics.publish-interval=PT10S",
            "pockethive.control-plane.swarm-controller.docker.socket-path=/var/run/docker.sock",
            "pockethive.control-plane.swarm-controller.metrics.adapter=CLICKHOUSE");
    for (String setting : settings.split(",")) {
      if (!setting.isEmpty()) {
        runner = runner.withPropertyValues("pockethive.control-plane.swarm-controller.metrics." + setting);
      }
    }
    runner.run(context -> assertThat(context).hasFailed());
  }

  @EnableConfigurationProperties(SwarmControllerProperties.class)
  private static class Config {}
}
