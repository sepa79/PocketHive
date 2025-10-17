package io.pockethive.generator;

import io.pockethive.controlplane.spring.BeeIdentityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationBeeNameTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

  @Test
  void bindsExternallyProvidedBeeName() {
    contextRunner
        .withPropertyValues("pockethive.control-plane.instance-id=external-generator-bee")
        .run(context ->
            assertThat(context.getBean(BeeIdentityProperties.class).beeName())
                .isEqualTo("external-generator-bee"));
  }

  @Test
  void failsWhenBeeNameMissing() {
    contextRunner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsWhenBeeNameBlank() {
    contextRunner
        .withPropertyValues("pockethive.control-plane.instance-id=   ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BeeIdentityProperties.class)
  static class TestConfiguration {}
}
