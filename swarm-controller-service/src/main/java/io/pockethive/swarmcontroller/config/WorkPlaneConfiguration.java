package io.pockethive.swarmcontroller.config;

import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.rabbit.work.RabbitWorkPlaneConfiguration;
import io.pockethive.rabbit.work.RabbitWorkTopologyResolver;
import io.pockethive.topology.work.WorkTopologyResolver;
import io.pockethive.rabbit.api.RabbitWorkTopologySettings;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Responsibility: compose the currently selected Rabbit WorkPlane infrastructure for this service.
 * Must not: implement Rabbit operations, naming or Work lifecycle behavior.
 * Contract: docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
@Configuration(proxyBeanMethods = false)
@Import(RabbitWorkPlaneConfiguration.class)
public class WorkPlaneConfiguration {
    @org.springframework.context.annotation.Bean
    public WorkTopologyResolver workTopologyResolver(SwarmControllerProperties properties) {
        var traffic = properties.getTraffic();
        var settings = new RabbitWorkTopologySettings(traffic.queuePrefix(), traffic.hiveExchange());
        return new RabbitWorkTopologyResolver(new RabbitResourceNames(), swarmId -> settings);
    }
}
