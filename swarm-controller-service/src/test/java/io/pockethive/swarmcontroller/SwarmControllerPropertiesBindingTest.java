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
            "pockethive.control-plane.swarm-controller.swarm-id=swarm-a",
            "pockethive.control-plane.swarm-controller.control-queue-prefix=ph.control")
        .run(
            context -> {
              SwarmControllerProperties properties =
                  context.getBean(SwarmControllerProperties.class);
              assertThat(properties.getSwarmId()).isEqualTo("swarm-a");
              assertThat(properties.getRole()).isEqualTo("swarm-controller");
              assertThat(properties.getTraffic().queuePrefix()).isEqualTo("ph.swarm-a");
              assertThat(properties.getRabbit().host()).isEqualTo("rabbitmq");
              assertThat(properties.getDocker().socketPath()).isEqualTo("/var/run/docker.sock");
            });
  }

  @EnableConfigurationProperties(SwarmControllerProperties.class)
  private static class Config {}
}
