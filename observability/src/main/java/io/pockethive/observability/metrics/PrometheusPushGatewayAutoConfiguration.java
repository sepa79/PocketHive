package io.pockethive.observability.metrics;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.BasicAuthHttpConnectionFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusProperties;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusProperties.Pushgateway;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager.ShutdownOperation;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

  @Bean
  @ConditionalOnBean(CollectorRegistry.class)
  @ConditionalOnProperty(
      prefix = "management.prometheus.metrics.export.pushgateway",
      name = "enabled",
      havingValue = "true")
  @ConditionalOnMissingBean(PrometheusPushGatewayManager.class)
  PrometheusPushGatewayManager pocketHiveLoggingPushGatewayManager(
      CollectorRegistry collectorRegistry,
      PrometheusProperties prometheusProperties,
      Environment environment) {
    Pushgateway pushgateway = prometheusProperties.getPushgateway();
    Duration pushRate = pushgateway.getPushRate();
    String job = resolveJob(pushgateway, environment);
    Map<String, String> groupingKey = pushgateway.getGroupingKey();
    ShutdownOperation shutdownOperation = pushgateway.getShutdownOperation();
    LoggingPushGateway pushGateway = createLoggingPushGateway(pushgateway.getBaseUrl());
    if (StringUtils.hasText(pushgateway.getUsername())) {
      pushGateway.setConnectionFactory(
          new BasicAuthHttpConnectionFactory(pushgateway.getUsername(), pushgateway.getPassword()));
    }
    log.info(
        "Configured Prometheus Pushgateway target {} (job={}, groupingKey={}, pushRate={}) for worker metrics",
        pushGateway.getGatewayBaseUrl(),
        job,
        groupingKey,
        pushRate);
    return new PrometheusPushGatewayManager(
        pushGateway, collectorRegistry, pushRate, job, groupingKey, shutdownOperation);
  }

  private LoggingPushGateway createLoggingPushGateway(String baseUrl) {
    if (!StringUtils.hasText(baseUrl)) {
      throw new IllegalStateException(
          "management.prometheus.metrics.export.pushgateway.base-url must be configured");
    }
    try {
      return new LoggingPushGateway(new URL(baseUrl));
    } catch (MalformedURLException ex) {
      throw new IllegalStateException("Invalid Prometheus Pushgateway base-url: " + baseUrl, ex);
    }
  }

  private String resolveJob(Pushgateway pushgateway, Environment environment) {
    if (pushgateway != null && StringUtils.hasText(pushgateway.getJob())) {
      return pushgateway.getJob();
    }
    String applicationName = environment.getProperty("spring.application.name");
    if (StringUtils.hasText(applicationName)) {
      return applicationName;
    }
    return "spring";
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
