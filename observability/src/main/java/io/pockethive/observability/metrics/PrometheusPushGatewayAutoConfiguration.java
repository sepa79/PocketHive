package io.pockethive.observability.metrics;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusPushMeterRegistry;
import io.micrometer.prometheus.PushGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(PrometheusPushMeterRegistry.class)
public class PrometheusPushGatewayAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PrometheusPushGatewayAutoConfiguration.class);

  @Bean
  @ConditionalOnBean(PrometheusPushMeterRegistry.class)
  MeterRegistryCustomizer<PrometheusPushMeterRegistry> pocketHivePushGatewayTags(Environment environment) {
    return registry -> {
      String swarmId = resolveFirstNonBlank(environment,
          "management.metrics.tags.swarm",
          "ph.swarmId",
          "PH_SWARM_ID");
      if (StringUtils.hasText(swarmId)) {
        registry.config().commonTags("swarm", swarmId);
      }
      String beeName = resolveFirstNonBlank(environment,
          "bee.name",
          "BEE_NAME",
          "management.metrics.tags.instance");
      if (StringUtils.hasText(beeName)) {
        registry.config().commonTags("bee", beeName);
      }
    };
  }

  @Bean
  @ConditionalOnBean(PrometheusPushMeterRegistry.class)
  ApplicationListener<ContextClosedEvent> pocketHivePushGatewayShutdownListener(PrometheusPushMeterRegistry registry) {
    return event -> cleanupPushGateway(registry);
  }

  private void cleanupPushGateway(PrometheusPushMeterRegistry registry) {
    PrometheusConfig config = registry.getConfig();
    if (config == null || !config.pushgatewayEnabled()) {
      return;
    }
    String baseUrl = config.pushgatewayBaseUrl();
    String job = config.pushgatewayJob();
    if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(job)) {
      return;
    }
    Map<String, String> groupingKey = config.pushgatewayGroupingKey();
    if (groupingKey == null) {
      groupingKey = Collections.emptyMap();
    }
    try {
      new PushGateway(baseUrl).delete(job, groupingKey);
      log.info("Deleted Pushgateway metrics for job '{}' and grouping key {}", job, groupingKey);
    } catch (IOException ex) {
      log.warn("Failed to delete Pushgateway metrics for job '{}' at {}", job, baseUrl, ex);
    }
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
