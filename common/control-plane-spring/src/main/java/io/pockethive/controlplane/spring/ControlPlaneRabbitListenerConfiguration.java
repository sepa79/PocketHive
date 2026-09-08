package io.pockethive.controlplane.spring;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: compose the dedicated Control Plane listener factory.
 * Must not: customize Work listener policy or own Work topology.
 * Contract: RESP-CP-LISTENER-POLICY — docs/architecture/runtime-responsibilities.md#resp-cp-listener-policy.
 */
@Configuration(proxyBeanMethods = false)
public class ControlPlaneRabbitListenerConfiguration {
    public static final String FACTORY_NAME = "controlPlaneRabbitListenerContainerFactory";

    @Bean(name = FACTORY_NAME)
    @ConditionalOnBean({ConnectionFactory.class, SimpleRabbitListenerContainerFactoryConfigurer.class})
    @ConditionalOnMissingBean(name = FACTORY_NAME)
    SimpleRabbitListenerContainerFactory controlPlaneRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory, SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        return factory;
    }
}
