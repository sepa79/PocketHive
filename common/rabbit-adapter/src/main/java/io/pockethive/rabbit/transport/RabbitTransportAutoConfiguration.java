package io.pockethive.rabbit.transport;

import io.pockethive.rabbit.api.RabbitPublisher;
import io.pockethive.rabbit.api.RabbitReceiver;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Responsibility: expose transport capabilities from the configured Rabbit connection.
 * Must not: own domain routing, envelopes or mutable plane policy.
 * Contract: docs/architecture/work-plane-boundaries.md#6-build-and-composition-enforcement.
 */
@AutoConfiguration(after = {RabbitAutoConfiguration.class, io.pockethive.rabbit.config.RabbitConnectionConfiguration.class})
@org.springframework.amqp.rabbit.annotation.EnableRabbit
@ConditionalOnBean(RabbitTemplate.class)
public class RabbitTransportAutoConfiguration {
    @Bean(name = io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER)
    @ConditionalOnMissingBean(name = io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER)
    RabbitPublisher rabbitPublisher(RabbitTemplate template) { return new SpringRabbitPublisher(template); }
    @Bean(name = io.pockethive.rabbit.api.RabbitTransportBeans.WORK_PUBLISHER)
    @ConditionalOnMissingBean(name = io.pockethive.rabbit.api.RabbitTransportBeans.WORK_PUBLISHER)
    RabbitPublisher workRabbitPublisher(io.pockethive.rabbit.config.WorkRabbitConnection work) {
        return new SpringRabbitPublisher(work.template());
    }
    @Bean @ConditionalOnMissingBean(RabbitReceiver.class)
    RabbitReceiver rabbitReceiver(io.pockethive.rabbit.config.WorkRabbitConnection work) { return new SpringRabbitReceiver(work.template()); }
    @Bean
    @ConditionalOnBean(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class)
    @ConditionalOnMissingBean(io.pockethive.rabbit.api.RabbitListeners.class)
    io.pockethive.rabbit.api.RabbitListeners rabbitListeners(
        org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry registry,
        org.springframework.amqp.rabbit.connection.ConnectionFactory connection,
        org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer configurer,
        io.pockethive.rabbit.config.WorkRabbitConnection work) {
        return new SpringRabbitListeners(registry, connection, work.connection(), configurer);
    }
    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton rabbitInboundRegistration(
        io.pockethive.rabbit.api.RabbitListeners listeners,
        org.springframework.beans.factory.ObjectProvider<io.pockethive.rabbit.api.RabbitListenerBinding> bindings) {
        return () -> bindings.orderedStream().forEach(listeners::register);
    }
}
