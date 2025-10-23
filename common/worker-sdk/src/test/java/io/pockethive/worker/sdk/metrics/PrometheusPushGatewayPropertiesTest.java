package io.pockethive.worker.sdk.metrics;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusPushGatewayPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(TestConfiguration.class);

  @Test
  void bindsPushGatewayPropertiesFromEnvironment() {
    contextRunner
        .withPropertyValues(
            "management.prometheus.metrics.export.pushgateway.enabled=true",
            "management.prometheus.metrics.export.pushgateway.base-url=http://pushgateway:9091",
            "management.prometheus.metrics.export.pushgateway.push-rate=PT10S",
            "management.prometheus.metrics.export.pushgateway.job=swarm-one",
            "management.prometheus.metrics.export.pushgateway.shutdown-operation=DELETE",
            "management.prometheus.metrics.export.pushgateway.grouping-key.instance=postprocessor-bee")
        .run(context -> {
          assertThat(context).hasSingleBean(PrometheusPushGatewayProperties.class);
          PrometheusPushGatewayProperties properties =
              context.getBean(PrometheusPushGatewayProperties.class);
          assertThat(properties.enabled()).isTrue();
          assertThat(properties.baseUrl()).isEqualTo("http://pushgateway:9091");
          assertThat(properties.pushRate()).isEqualTo(Duration.ofSeconds(10));
          assertThat(properties.job()).isEqualTo("swarm-one");
          assertThat(properties.shutdownOperation()).isEqualTo("DELETE");
          assertThat(properties.groupingKey().instance()).isEqualTo("postprocessor-bee");
        });
  }

  @Test
  void failsWhenAnyPropertyMissing() {
    contextRunner.run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PrometheusPushGatewayProperties.class)
  static class TestConfiguration {}
}
