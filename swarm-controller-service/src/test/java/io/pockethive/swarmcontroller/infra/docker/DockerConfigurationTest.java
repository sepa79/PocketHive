package io.pockethive.swarmcontroller.infra.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DockerConfigurationTest {

  private static final String SWARM_ID = "default";
  private static final String CONTROL_EXCHANGE = "ph.control";
  private static final String CONTROL_QUEUE_PREFIX_BASE = "ph.control";
  private static final String TRAFFIC_PREFIX = "ph." + SWARM_ID;
  private static final String HIVE_EXCHANGE = TRAFFIC_PREFIX + ".hive";
  private static final SwarmControllerProperties.Metrics METRICS =
      new SwarmControllerProperties.Metrics(
          PocketHiveMetricsAdapter.PROMETHEUS_PUSHGATEWAY,
          Duration.ofSeconds(10),
          new SwarmControllerProperties.Pushgateway(
              true,
              "http://pushgateway:9091",
              Duration.ofSeconds(30),
              "DELETE",
              "test-job",
              new SwarmControllerProperties.GroupingKey("controller-instance")),
          ClickHouseMetricsSinkProperties.disabled());

  @Test
  void dockerClientConfigHonorsConfiguredHost() {
    SwarmControllerProperties properties = propertiesWithDocker(
        new SwarmControllerProperties.Docker("unix:///custom/docker.sock", "/var/run/docker.sock", null));
    DockerConfiguration configuration = new DockerConfiguration(properties);

    DefaultDockerClientConfig config = configuration.dockerClientConfig();

    assertThat(config.getDockerHost().toString()).isEqualTo("unix:///custom/docker.sock");
  }

  @Test
  void dockerClientConfigFallsBackToSocketPath() {
    SwarmControllerProperties properties = propertiesWithDocker(
        new SwarmControllerProperties.Docker(null, "/custom/docker.sock", null));
    DockerConfiguration configuration = new DockerConfiguration(properties);

    DefaultDockerClientConfig config = configuration.dockerClientConfig();

    assertThat(config.getDockerHost().toString()).isEqualTo("unix:///custom/docker.sock");
  }

  private SwarmControllerProperties propertiesWithDocker(SwarmControllerProperties.Docker docker) {
    return new SwarmControllerProperties(
        SWARM_ID,
        CONTROL_EXCHANGE,
        CONTROL_QUEUE_PREFIX_BASE,
        new SwarmControllerProperties.Manager("swarm-controller"),
        new SwarmControllerProperties.SwarmController(
            new SwarmControllerProperties.Traffic(
                HIVE_EXCHANGE,
                TRAFFIC_PREFIX),
            METRICS,
            docker,
            new SwarmControllerProperties.Features(false)));
  }
}
