package io.pockethive.orchestrator.config;

import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.rabbit.work.RabbitWorkTopologyResolver;
import io.pockethive.topology.work.WorkDebugTaps;
import io.pockethive.rabbit.work.RabbitWorkDebugTaps;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitReceiver;
import io.pockethive.rabbit.api.RabbitResourceBeans;
import org.springframework.beans.factory.annotation.Qualifier;
import io.pockethive.topology.work.WorkTopologyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: select Orchestrator's explicit Work resource-name capability for manifest projections.
 * Must not: resolve names, provision resources or own lifecycle decisions.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
@Configuration(proxyBeanMethods = false)
public class WorkTopologyConfiguration {
    @Bean
    WorkDebugTaps workDebugTaps(@Qualifier(RabbitResourceBeans.WORK) RabbitResources resources, RabbitReceiver receiver) {
        return new RabbitWorkDebugTaps(resources, receiver);
    }

    @Bean
    WorkTopologyResolver workTopologyResolver() {
        return new RabbitWorkTopologyResolver(new RabbitResourceNames(), new RabbitResourceNames()::forSwarm);
    }
}
