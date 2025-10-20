package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared auto-configuration that exposes reusable beans for control-plane components.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(RabbitAutoConfiguration.class)
@ConditionalOnClass({TopicExchange.class, RabbitTemplate.class})
@ConditionalOnProperty(prefix = "pockethive.control-plane", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({ControlPlaneProperties.class, BeeIdentityProperties.class})
public class ControlPlaneCommonAutoConfiguration {

    @Bean(name = "controlPlaneExchange")
    @ConditionalOnMissingBean(name = "controlPlaneExchange")
    TopicExchange controlPlaneExchange(ObjectProvider<ControlPlaneProperties> managerProperties,
                                       ObjectProvider<WorkerControlPlaneProperties> workerProperties) {
        String exchange = resolveExchange(managerProperties, workerProperties);
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
    ControlPlanePublisher controlPlanePublisher(RabbitTemplate template,
                                                ObjectProvider<ControlPlaneProperties> managerProperties,
                                                ObjectProvider<WorkerControlPlaneProperties> workerProperties) {
        String exchange = resolveExchange(managerProperties, workerProperties);
        return new AmqpControlPlanePublisher(template, exchange);
    }

    private static String resolveExchange(ObjectProvider<ControlPlaneProperties> managerProperties,
                                          ObjectProvider<WorkerControlPlaneProperties> workerProperties) {
        ControlPlaneProperties manager = managerProperties.getIfAvailable();
        if (manager != null && manager.getExchange() != null && !manager.getExchange().isBlank()) {
            return manager.getExchange();
        }
        WorkerControlPlaneProperties worker = workerProperties.getIfAvailable();
        if (worker != null && worker.getExchange() != null && !worker.getExchange().isBlank()) {
            return worker.getExchange();
        }
        throw new IllegalArgumentException("pockethive.control-plane.exchange must not be null or blank");
    }

}
