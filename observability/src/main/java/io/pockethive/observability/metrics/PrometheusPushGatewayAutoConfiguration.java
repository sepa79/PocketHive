package io.pockethive.observability.metrics;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@AutoConfiguration
@ConditionalOnClass({PrometheusMeterRegistry.class, PrometheusPushGatewayManager.class})
public class PrometheusPushGatewayAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PrometheusPushGatewayAutoConfiguration.class);

  @Bean
  @ConditionalOnBean(PrometheusMeterRegistry.class)
  MeterRegistryCustomizer<PrometheusMeterRegistry> pocketHivePushGatewayTags(Environment environment) {
    return registry -> {
      String swarmId = resolveFirstNonBlank(environment,
          "management.metrics.tags.swarm",
          "pockethive.control-plane.swarm-id",
          "POCKETHIVE_CONTROL_PLANE_SWARM_ID");
      if (StringUtils.hasText(swarmId)) {
        registry.config().commonTags("swarm", swarmId);
      }
      String instanceId = resolveFirstNonBlank(environment,
          "management.metrics.tags.instance",
          "pockethive.control-plane.instance-id",
          "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID");
      if (StringUtils.hasText(instanceId)) {
        registry.config().commonTags("instance", instanceId);
      }
    };
  }

  @Bean
  @ConditionalOnBean(PrometheusMeterRegistry.class)
  ApplicationListener<ContextClosedEvent> pocketHivePushGatewayShutdownListener(
      ObjectProvider<PrometheusPushGatewayManager> managerProvider) {
    return event -> managerProvider.ifAvailable(manager -> {
      try {
        manager.shutdown();
        log.info("Invoked Prometheus Pushgateway shutdown cleanup");
      } catch (Exception ex) {
        log.warn("Failed to invoke Prometheus Pushgateway shutdown cleanup", ex);
      }
    });
  }

  private String resolveFirstNonBlank(Environment environment, String... keys) {
    if (keys == null) {
      return null;
    }
    for (String key : keys) {
      if (key == null) {
        continue;
      }
      String value = environment.getProperty(key);
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }
}
