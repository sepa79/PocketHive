package io.pockethive.controlplane.spring;

import io.pockethive.Topology;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import java.util.Objects;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared auto-configuration that exposes reusable beans for control-plane components.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(RabbitAutoConfiguration.class)
@ConditionalOnClass({TopicExchange.class, RabbitTemplate.class})
@ConditionalOnProperty(prefix = "pockethive.control-plane", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ControlPlaneProperties.class)
public class ControlPlaneCommonAutoConfiguration {

    @Bean(name = "controlPlaneExchange")
    @ConditionalOnMissingBean(name = "controlPlaneExchange")
    TopicExchange controlPlaneExchange(ControlPlaneProperties properties) {
        String exchange = requireText(properties.getExchange(), "pockethive.control-plane.exchange");
        return ExchangeBuilder.topicExchange(exchange).durable(true).build();
    }

    @Bean(name = "swarmWorkExchange")
    @ConditionalOnMissingBean(name = "swarmWorkExchange")
    TopicExchange swarmWorkExchange(ControlPlaneProperties properties) {
        Objects.requireNonNull(properties, "properties");
        String exchange = resolveWorkExchange(properties);
        return ExchangeBuilder.topicExchange(exchange).durable(true).build();
    }

    @Bean
    @ConditionalOnMissingBean
    ControlPlaneTopologyDeclarableFactory controlPlaneTopologyDeclarableFactory() {
        return new ControlPlaneTopologyDeclarableFactory();
    }

    @Bean
    @ConditionalOnBean(RabbitTemplate.class)
    @ConditionalOnMissingBean(ControlPlanePublisher.class)
    @ConditionalOnProperty(prefix = "pockethive.control-plane.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
    ControlPlanePublisher controlPlanePublisher(RabbitTemplate template, ControlPlaneProperties properties) {
        String exchange = requireText(properties.getExchange(), "pockethive.control-plane.exchange");
        return new AmqpControlPlanePublisher(template, exchange);
    }

    private static String requireText(String value, String propertyName) {
        Objects.requireNonNull(propertyName, "propertyName");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be null or blank");
        }
        return value;
    }

    private static String resolveWorkExchange(ControlPlaneProperties properties) {
        String exchange = firstNonBlank(System.getenv("PH_TRAFFIC_EXCHANGE"),
            System.getProperty("PH_TRAFFIC_EXCHANGE"));
        if (isText(exchange)) {
            return exchange;
        }

        String override = firstNonBlank(properties.getWorker().getSwarmId(),
            properties.getManager().getSwarmId());
        String swarmId = properties.resolveSwarmId(override);
        return "ph." + swarmId + ".hive";
    }

    private static String firstNonBlank(String first, String second) {
        if (isText(first)) {
            return first;
        }
        return second;
    }

    private static boolean isText(String value) {
        return value != null && !value.isBlank();
    }
}
