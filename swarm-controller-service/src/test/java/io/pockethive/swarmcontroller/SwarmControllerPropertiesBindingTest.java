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
            "pockethive.control-plane.swarm-controller.traffic.queue-prefix=ph.swarm-a",
            "pockethive.control-plane.swarm-controller.traffic.hive-exchange=ph.swarm-a.hive",
            "pockethive.control-plane.swarm-controller.metrics.adapter=DISABLED",
            "pockethive.control-plane.swarm-controller.metrics.publish-interval=PT10S",
            "pockethive.control-plane.swarm-controller.docker.socket-path=/var/run/docker.sock")
        .run(
            context -> {
              SwarmControllerProperties properties =
                  context.getBean(SwarmControllerProperties.class);
              assertThat(properties.getSwarmId()).isEqualTo("swarm-a");
              assertThat(properties.getRole()).isEqualTo("swarm-controller");
              assertThat(properties.getTraffic().queuePrefix()).isEqualTo("ph.swarm-a");
              assertThat(properties.hiveExchange()).isEqualTo("ph.swarm-a.hive");
              assertThat(properties.queueName("final")).isEqualTo("ph.swarm-a.final");
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
            "pockethive.control-plane.swarm-controller.traffic.queue-prefix=ph.swarm-a",
            "pockethive.control-plane.swarm-controller.traffic.hive-exchange=ph.swarm-a.hive",
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
            });
  }

  @EnableConfigurationProperties(SwarmControllerProperties.class)
  private static class Config {}
}
