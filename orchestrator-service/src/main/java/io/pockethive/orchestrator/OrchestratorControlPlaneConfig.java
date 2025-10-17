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
import io.pockethive.util.BeeNameGenerator;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OrchestratorControlPlaneConfig {

    private static final String ROLE = "orchestrator";
    private static final String BEE_NAME_PROPERTY = "bee.name";
    private static final String INSTANCE_ID_PROPERTY = "pockethive.control-plane.instance-id";

    @Bean
    String instanceId(ControlPlaneProperties properties) {
        Objects.requireNonNull(properties, "properties");
        String resolved = normalise(properties.getInstanceId());
        if (resolved == null) {
            resolved = normalise(System.getProperty(INSTANCE_ID_PROPERTY));
        }
        if (resolved == null) {
            resolved = normalise(System.getProperty(BEE_NAME_PROPERTY));
        }
        if (resolved == null) {
            resolved = BeeNameGenerator.generate(ROLE, resolveManagerSwarmId(properties));
        }
        if (resolved == null) {
            throw new IllegalStateException("Manager instance id could not be resolved");
        }
        properties.setInstanceId(resolved);
        System.setProperty(BEE_NAME_PROPERTY, resolved);
        System.setProperty(INSTANCE_ID_PROPERTY, resolved);
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
        String swarmId = requireText(properties.getSwarmId(), "pockethive.control-plane.swarm-id");
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

    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must not be null or blank");
        }
        return value;
    }
}
