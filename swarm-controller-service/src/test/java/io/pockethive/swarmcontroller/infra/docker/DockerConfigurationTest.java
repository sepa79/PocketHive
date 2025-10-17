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
    SwarmControllerProperties properties = new SwarmControllerProperties(
        Topology.SWARM_ID,
        "swarm-controller",
        Topology.CONTROL_EXCHANGE,
        "ph.control",
        new SwarmControllerProperties.Traffic(null, null),
        new SwarmControllerProperties.Rabbit("rabbitmq", "ph.logs"),
        new SwarmControllerProperties.Metrics(
            new SwarmControllerProperties.Pushgateway(false, null, Duration.ofMinutes(1), "DELETE")),
        new SwarmControllerProperties.Docker("unix:///custom/docker.sock", "/var/run/docker.sock"));
    DockerConfiguration configuration = new DockerConfiguration(properties);

    DefaultDockerClientConfig config = configuration.dockerClientConfig();

    assertThat(config.getDockerHost().toString()).isEqualTo("unix:///custom/docker.sock");
  }

  @Test
  void dockerClientConfigFallsBackToSocketPath() {
    SwarmControllerProperties properties = new SwarmControllerProperties(
        Topology.SWARM_ID,
        "swarm-controller",
        Topology.CONTROL_EXCHANGE,
        "ph.control",
        new SwarmControllerProperties.Traffic(null, null),
        new SwarmControllerProperties.Rabbit("rabbitmq", "ph.logs"),
        new SwarmControllerProperties.Metrics(
            new SwarmControllerProperties.Pushgateway(false, null, Duration.ofMinutes(1), "DELETE")),
        new SwarmControllerProperties.Docker(null, "/custom/docker.sock"));
    DockerConfiguration configuration = new DockerConfiguration(properties);

    DefaultDockerClientConfig config = configuration.dockerClientConfig();

    assertThat(config.getDockerHost().toString()).isEqualTo("unix:///custom/docker.sock");
  }
}
