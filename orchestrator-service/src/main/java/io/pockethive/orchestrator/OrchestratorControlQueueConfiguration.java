package io.pockethive.orchestrator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: Project resolved Orchestrator queues and receive bindings from the shared topology descriptor.
 * Must not: Construct queue names, routing bindings, or RabbitMQ declarables.
 * Contract: RESP-CP-DECLARATIONS — docs/architecture/runtime-responsibilities.md#resp-cp-declarations.
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
    @Bean
    io.pockethive.rabbit.api.RabbitListenerBinding managerControlRabbitBinding(
        io.pockethive.controlplane.spring.ControlPlaneRabbitBindings bindings,
        io.pockethive.orchestrator.app.SwarmSignalListener listener,
        @Qualifier("managerControlQueueName") String queue) {
        return bindings.bind("orchestratorControl", queue,
            message -> listener.handle(message.text(), message.receivedRoutingKey()));
    }

    @Bean
    io.pockethive.rabbit.api.RabbitListenerBinding controllerStatusRabbitBinding(
        io.pockethive.controlplane.spring.ControlPlaneRabbitBindings bindings,
        io.pockethive.orchestrator.app.ControllerStatusListener listener,
        @Qualifier("controllerStatusQueueName") String queue) {
        return bindings.bind("orchestratorControllerStatus", queue,
            message -> listener.handle(message.text(), message.receivedRoutingKey()));
    }
}
