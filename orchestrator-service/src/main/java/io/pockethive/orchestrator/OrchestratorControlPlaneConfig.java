package io.pockethive.orchestrator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.QueueDescriptor;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OrchestratorControlPlaneConfig {

    private static final String ROLE = "orchestrator";
    private static final String INSTANCE_ID_PROPERTY = "pockethive.control-plane.instance-id";
    private static final String SWARM_ID_PROPERTY = "pockethive.control-plane.swarm-id";

    @Bean
    String instanceId(ControlPlaneProperties properties) {
        Objects.requireNonNull(properties, "properties");
        String resolved = requireText(properties.getInstanceId(), INSTANCE_ID_PROPERTY);
        properties.setInstanceId(resolved);
        return resolved;
    }

    @Bean(name = "managerControlPlaneTopologyDescriptor")
    @ConditionalOnMissingBean(name = "managerControlPlaneTopologyDescriptor")
    ControlPlaneTopologyDescriptor managerControlPlaneTopologyDescriptor() {
        return ControlPlaneTopologyDescriptorFactory.forManagerRole(ROLE);
    }

    @Bean(name = "managerControlPlaneIdentity")
    @ConditionalOnMissingBean(name = "managerControlPlaneIdentity")
    ControlPlaneIdentity managerControlPlaneIdentity(
        ControlPlaneProperties properties,
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("instanceId") String instanceId) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(instanceId, "instanceId");
        String swarmId = requireText(properties.getSwarmId(), SWARM_ID_PROPERTY);
        return new ControlPlaneIdentity(swarmId, descriptor.role(), instanceId);
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
        return ControlPlaneEmitter.using(descriptor, role, publisher);
    }

    @Bean(name = "managerControlQueueName")
    String managerControlQueueName(
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(identity, "identity");
        return descriptor.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow(() -> new IllegalStateException("Orchestrator control queue descriptor is missing"));
    }

    @Bean(name = "controllerStatusQueueName")
    String controllerStatusQueueName(
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(identity, "identity");
        return descriptor.additionalQueues(identity.instanceId()).stream()
            .findFirst()
            .map(QueueDescriptor::name)
            .orElseThrow(() -> new IllegalStateException("Orchestrator status queue descriptor is missing"));
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must not be null or blank");
        }
        return value;
    }
}
