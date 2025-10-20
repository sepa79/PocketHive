package io.pockethive.swarmcontroller.infra.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import io.pockethive.Topology;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DockerConfigurationTest {

  @Test
  void dockerClientConfigHonorsConfiguredHost() {
    SwarmControllerProperties properties = propertiesWithDocker(
        new SwarmControllerProperties.Docker("unix:///custom/docker.sock", "/var/run/docker.sock"));
    DockerConfiguration configuration = new DockerConfiguration(properties);

    DefaultDockerClientConfig config = configuration.dockerClientConfig();

    assertThat(config.getDockerHost().toString()).isEqualTo("unix:///custom/docker.sock");
  }

  @Test
  void dockerClientConfigFallsBackToSocketPath() {
    SwarmControllerProperties properties = propertiesWithDocker(
        new SwarmControllerProperties.Docker(null, "/custom/docker.sock"));
    DockerConfiguration configuration = new DockerConfiguration(properties);

    DefaultDockerClientConfig config = configuration.dockerClientConfig();

    assertThat(config.getDockerHost().toString()).isEqualTo("unix:///custom/docker.sock");
  }

  private SwarmControllerProperties propertiesWithDocker(SwarmControllerProperties.Docker docker) {
    return new SwarmControllerProperties(
        Topology.SWARM_ID,
        Topology.CONTROL_EXCHANGE,
        new SwarmControllerProperties.Manager("swarm-controller"),
        new SwarmControllerProperties.SwarmController(
            "ph.control",
            new SwarmControllerProperties.Traffic(
                "ph." + Topology.SWARM_ID + ".hive",
                "ph." + Topology.SWARM_ID),
            new SwarmControllerProperties.Rabbit("ph.logs"),
            new SwarmControllerProperties.Metrics(
                new SwarmControllerProperties.Pushgateway(false, null, Duration.ofMinutes(1), "DELETE")),
            docker));
  }
}
