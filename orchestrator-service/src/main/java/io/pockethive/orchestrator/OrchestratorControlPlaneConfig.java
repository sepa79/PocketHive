package io.pockethive.orchestrator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OrchestratorControlPlaneConfig {

    private final OrchestratorProperties properties;

    OrchestratorControlPlaneConfig(
        ControlPlaneProperties controlPlaneProperties,
        OrchestratorProperties orchestratorProperties) {
        Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
        this.properties = Objects.requireNonNull(orchestratorProperties, "orchestratorProperties");
    }

    @Bean
    ControlPlaneEmitter orchestratorControlPlaneEmitter(
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity,
        ControlPlanePublisher publisher) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(publisher, "publisher");
        RoleContext role = RoleContext.fromIdentity(identity);
        return ControlPlaneEmitter.using(descriptor, role, publisher, null);
    }

    @Bean(name = "managerControlQueueName")
    String managerControlQueueName(@Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        String prefix = properties.getControlQueuePrefix();
        return queueName(prefix, identity.instanceId());
    }

    @Bean(name = "controllerStatusQueueName")
    String controllerStatusQueueName(@Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        String prefix = properties.getStatusQueuePrefix();
        return queueName(prefix, identity.instanceId());
    }

    private static String queueName(String prefix, String instanceId) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("Queue prefix must not be null or blank");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException("Control-plane instance id must not be null or blank");
        }
        return prefix + "." + instanceId;
    }
}
