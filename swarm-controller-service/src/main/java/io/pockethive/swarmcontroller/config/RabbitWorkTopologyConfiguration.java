package io.pockethive.swarmcontroller.config;

import io.pockethive.rabbit.api.RabbitControllerTopologyEnvironment;
import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.rabbit.api.RabbitWorkPlaneCondition;
import io.pockethive.rabbit.work.RabbitWorkTopologyResolver;
import io.pockethive.topology.work.WorkTopologyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Responsibility: compose the selected Rabbit Work topology from its owner's provisioned settings.
 * Must not: require Rabbit WORK fields for other adapters or reconstruct resource names.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(RabbitWorkPlaneCondition.class)
public class RabbitWorkTopologyConfiguration {
    @Bean public WorkTopologyResolver workTopologyResolver(Environment environment) {
        var settings = RabbitControllerTopologyEnvironment.decode(environment::getProperty);
        return new RabbitWorkTopologyResolver(new RabbitResourceNames(), swarmId -> settings);
    }
}
