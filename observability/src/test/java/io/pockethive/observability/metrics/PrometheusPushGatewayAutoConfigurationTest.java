package io.pockethive.observability.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ReflectionUtils;

class PrometheusPushGatewayAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PrometheusPushGatewayAutoConfiguration.class))
          .withPropertyValues(
              "management.prometheus.metrics.export.pushgateway.enabled=true",
              "management.prometheus.metrics.export.pushgateway.base-url=http://localhost:9091",
              "spring.application.name=postprocessor-service")
          .withBean(CollectorRegistry.class, CollectorRegistry::new)
          .withBean(PrometheusMeterRegistry.class, () -> new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

  @Test
  void usesLoggingPushGatewayImplementation() {
    contextRunner.run(context -> {
      String[] beanNames = context.getBeanNamesForType(org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager.class);
      assertEquals(1, beanNames.length, "Expected a single PrometheusPushGatewayManager bean");

      org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager manager =
          context.getBean(org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager.class);
      Object pushGateway = extractPushGateway(manager);
      assertNotNull(pushGateway, "PushGateway should not be null");
      assertEquals(LoggingPushGateway.class, pushGateway.getClass(), "LoggingPushGateway should be wired");
      assertEquals("http://localhost:9091", ((LoggingPushGateway) pushGateway).getGatewayBaseUrl());
    });
  }

  private Object extractPushGateway(
      org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager manager) {
    Field field = ReflectionUtils.findField(
        org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager.class,
        "pushGateway");
    assertNotNull(field, "pushGateway field should exist");
    ReflectionUtils.makeAccessible(field);
    return ReflectionUtils.getField(field, manager);
  }
}
