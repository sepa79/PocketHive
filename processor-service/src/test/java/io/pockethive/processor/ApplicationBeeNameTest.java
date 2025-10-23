package io.pockethive.processor;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationBeeNameTest {

  private static final String[] REQUIRED_PROPERTIES = {
      "pockethive.control-plane.exchange=test-exchange",
      "pockethive.control-plane.control-queue-prefix=test-control-prefix",
      "pockethive.control-plane.swarm-id=test-swarm"
  };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(TestConfiguration.class)
          .withPropertyValues(REQUIRED_PROPERTIES);

  @Test
  void bindsExternallyProvidedBeeName() {
    contextRunner
        .withPropertyValues("pockethive.control-plane.instance-id=external-processor-bee")
        .run(context ->
            assertThat(context.getBean(ControlPlaneProperties.class).getInstanceId())
                .isEqualTo("external-processor-bee"));
  }

  @Test
  void failsWhenBeeNameMissing() {
    contextRunner.run(ApplicationBeeNameTest::assertMissingInstanceIdFailure);
  }

  @Test
  void failsWhenBeeNameBlank() {
    contextRunner
        .withPropertyValues("pockethive.control-plane.instance-id=   ")
        .run(ApplicationBeeNameTest::assertMissingInstanceIdFailure);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ControlPlaneProperties.class)
  static class TestConfiguration {}

  private static void assertMissingInstanceIdFailure(AssertableApplicationContext context) {
    assertThat(context).hasFailed();
    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
    BindValidationException validationException = findCause(context.getStartupFailure(), BindValidationException.class);
    if (validationException != null) {
      assertThat(validationException.getValidationErrors().getAllErrors())
          .anySatisfy(error -> assertThat(error.toString()).contains("identity.instanceId"));
      return;
    }
    IllegalArgumentException illegalArgument = findCause(context.getStartupFailure(), IllegalArgumentException.class);
    assertThat(illegalArgument).isNotNull();
    assertThat(illegalArgument.getMessage()).contains("pockethive.control-plane.instance-id");
  }

  private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
    Throwable current = throwable;
    while (current != null) {
      if (type.isInstance(current)) {
        return type.cast(current);
      }
      current = current.getCause();
    }
    return null;
  }
}
