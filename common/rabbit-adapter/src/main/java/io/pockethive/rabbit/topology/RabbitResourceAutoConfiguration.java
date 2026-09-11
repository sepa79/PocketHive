package io.pockethive.rabbit.topology;

import io.pockethive.rabbit.api.RabbitResources;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Responsibility: expose the resource API from the application's configured broker connection.
 * Must not: select domain topology or share mutable plane policy.
 * Contract: docs/architecture/work-plane-boundaries.md#6-build-and-composition-enforcement.
 */
@AutoConfiguration(after = {RabbitAutoConfiguration.class, io.pockethive.rabbit.config.RabbitConnectionConfiguration.class})
public class RabbitResourceAutoConfiguration {
    @Bean
    org.springframework.amqp.core.Declarables rabbitDeclarations(
        org.springframework.beans.factory.ObjectProvider<io.pockethive.rabbit.api.RabbitExchangeSpec> exchanges,
        org.springframework.beans.factory.ObjectProvider<io.pockethive.rabbit.api.RabbitTopologySpec> topologies) {
        var declarations = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
        exchanges.orderedStream().map(RabbitDeclarations::exchange).forEach(declarations::add);
        topologies.orderedStream().forEach(topology -> {
            topology.queues().stream().map(RabbitDeclarations::queue).forEach(declarations::add);
            topology.bindings().stream().map(RabbitDeclarations::binding).forEach(declarations::add);
        });
        return new org.springframework.amqp.core.Declarables(declarations);
    }

    @Bean(name = io.pockethive.rabbit.api.RabbitResourceBeans.CONTROL)
    @ConditionalOnBean(RabbitTemplate.class)
    @ConditionalOnMissingBean(name = io.pockethive.rabbit.api.RabbitResourceBeans.CONTROL)
    RabbitResources controlRabbitResources(RabbitTemplate template) { return new SpringRabbitResources(new RabbitAdmin(template)); }

    @Bean(name = io.pockethive.rabbit.api.RabbitResourceBeans.WORK)
    @ConditionalOnBean(RabbitTemplate.class)
    @ConditionalOnMissingBean(name = io.pockethive.rabbit.api.RabbitResourceBeans.WORK)
    RabbitResources workRabbitResources(io.pockethive.rabbit.config.WorkRabbitConnection work) { return new SpringRabbitResources(new RabbitAdmin(work.template())); }
}
