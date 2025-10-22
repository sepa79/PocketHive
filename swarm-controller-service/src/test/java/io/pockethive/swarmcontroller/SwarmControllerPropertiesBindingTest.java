package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
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
            "pockethive.control-plane.swarm-controller.rabbit.logs-exchange=ph.logs",
            "pockethive.control-plane.swarm-controller.docker.socket-path=/var/run/docker.sock")
        .run(
            context -> {
              SwarmControllerProperties properties =
                  context.getBean(SwarmControllerProperties.class);
              assertThat(properties.getSwarmId()).isEqualTo("swarm-a");
              assertThat(properties.getRole()).isEqualTo("swarm-controller");
              assertThat(properties.getTraffic().queuePrefix()).isEqualTo("ph.swarm-a");
              assertThat(properties.hiveExchange()).isEqualTo("ph.swarm-a.hive");
              assertThat(properties.getRabbit().logsExchange()).isEqualTo("ph.logs");
              assertThat(properties.getRabbit().logging().enabled()).isFalse();
              assertThat(properties.getDocker().socketPath()).isEqualTo("/var/run/docker.sock");
            });
  }

  @EnableConfigurationProperties(SwarmControllerProperties.class)
  private static class Config {}
}
