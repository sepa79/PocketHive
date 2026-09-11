package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.spring.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import java.util.Map;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
 * <p>
 * Responsibility: compose shared Control Plane infrastructure.
 * Must not: configure Work listeners or declare Work resources.
 * Contract: RESP-CP-COMPOSITION — docs/architecture/runtime-responsibilities.md#resp-cp-composition.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = {"org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
    "io.pockethive.rabbit.transport.RabbitTransportAutoConfiguration"})
@ConditionalOnClass({RabbitExchangeSpec.class, RabbitPublisher.class})
@ConditionalOnProperty(prefix = "pockethive.control-plane", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ControlPlaneProperties.class)
public class ControlPlaneCommonAutoConfiguration {

    @Bean(name = "controlPlaneExchange")
    @ConditionalOnMissingBean(name = "controlPlaneExchange")
    RabbitExchangeSpec controlPlaneExchange(ObjectProvider<ControlPlaneProperties> managerProperties,
                                       ObjectProvider<WorkerControlPlaneProperties> workerProperties) {
        String exchange = resolveExchange(managerProperties, workerProperties);
        return new RabbitExchangeSpec(exchange, true, false, Map.of());
    }

    @Bean
    @ConditionalOnMissingBean
    ControlPlaneCodec controlPlaneCodec() {
        return ControlPlaneCodec.create();
    }

    @Bean
    @ConditionalOnMissingBean
    ControlPlaneTopologyDeclarableFactory controlPlaneTopologyDeclarableFactory() {
        return new ControlPlaneTopologyDeclarableFactory();
    }

    @Bean
    @ConditionalOnBean(RabbitPublisher.class)
    @ConditionalOnMissingBean(ControlPlanePublisher.class)
    @ConditionalOnProperty(prefix = "pockethive.control-plane.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
    ControlPlanePublisher controlPlanePublisher(@org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER) RabbitPublisher template,
                                                ControlPlaneCodec codec,
                                                ObjectProvider<ControlPlaneProperties> managerProperties,
                                                ObjectProvider<WorkerControlPlaneProperties> workerProperties) {
        String exchange = resolveExchange(managerProperties, workerProperties);
        return new AmqpControlPlanePublisher(template, exchange, codec);
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

    @Bean
    ControlPlaneRabbitBindings controlPlaneRabbitBindings(
        @org.springframework.beans.factory.annotation.Value("${pockethive.control-plane.rabbit.poison-messages.enabled:true}")
        boolean rejectPoisonMessages) {
        return new ControlPlaneRabbitBindings(rejectPoisonMessages);
    }
}
