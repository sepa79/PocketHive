package io.pockethive.orchestrator.config;

import io.pockethive.topology.work.WorkResourceNamesPort;
import io.pockethive.rabbit.api.RabbitResourceNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: select Orchestrator's explicit Work resource-name capability for manifest projections.
 * Must not: resolve names, provision resources or own lifecycle decisions.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
@Configuration(proxyBeanMethods = false)
public class WorkResourceNamesConfiguration {
    @Bean
    WorkResourceNamesPort workResourceNames() { return new RabbitResourceNames(); }
}
