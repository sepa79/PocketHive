package io.pockethive.logaggregator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.springframework.boot.context.properties.bind.validation.BindValidationException;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(Application.class);

  @Test
  void bindsDefaultsFromConfigurationFile() {
    contextRunner
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              LogAggregatorControlPlaneProperties properties =
                  context.getBean(LogAggregatorControlPlaneProperties.class);

              assertThat(properties.swarmId()).isEqualTo("hive");
              assertThat(properties.manager().instanceId()).isEqualTo("log-aggregator");
              assertThat(properties.manager().role()).isEqualTo("log-aggregator");
            });
  }

  @Test
  void bindsControlPlanePropertiesFromConfiguration() {
    contextRunner
        .withPropertyValues(
            "pockethive.control-plane.swarm-id=swarm-from-config",
            "pockethive.control-plane.manager.instance-id=bee-from-config")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              LogAggregatorControlPlaneProperties properties =
                  context.getBean(LogAggregatorControlPlaneProperties.class);

              assertThat(properties.swarmId()).isEqualTo("swarm-from-config");
              assertThat(properties.manager().instanceId()).isEqualTo("bee-from-config");
            });
  }

  @Test
  void failsWhenManagerInstanceIdBlank() {
    contextRunner
        .withPropertyValues(
            "pockethive.control-plane.swarm-id=swarm-only",
            "pockethive.control-plane.manager.instance-id=")
          .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(BindValidationException.class);
            });
  }
}
