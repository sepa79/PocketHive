package io.pockethive.orchestrator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: Expose read-only listener queue names from the shared Orchestrator topology descriptor.
 * Must not: Construct queue names, routing bindings, or RabbitMQ declarables.
 * Contract: docs/orchestrator/configuration.md; shared manager auto-configuration declares the topology.
 */
@Configuration(proxyBeanMethods = false)
class OrchestratorControlQueueConfiguration {

    private final OrchestratorControlPlaneTopologyDescriptor descriptor;
    private final ControlPlaneIdentity identity;

    OrchestratorControlQueueConfiguration(
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        if (!(descriptor instanceof OrchestratorControlPlaneTopologyDescriptor orchestrator)) {
            throw new IllegalArgumentException("Orchestrator requires its canonical control-plane topology descriptor");
        }
        this.descriptor = orchestrator;
        this.identity = identity;
    }

    @Bean
    String managerControlQueueName() {
        return descriptor.controlQueue(identity.instanceId()).orElseThrow().name();
    }

    @Bean
    String controllerStatusQueueName() {
        return descriptor.controllerStatusQueue(identity.instanceId()).name();
    }
}
