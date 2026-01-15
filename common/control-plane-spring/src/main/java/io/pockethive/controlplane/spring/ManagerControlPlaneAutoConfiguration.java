package io.pockethive.controlplane.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.consumer.SelfFilter;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that exposes manager-facing control-plane beans.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ManagerControlPlane.class)
@ConditionalOnProperty(prefix = "pockethive.control-plane.manager", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ManagerControlPlaneAutoConfiguration {

    private final ControlPlaneProperties properties;

    ManagerControlPlaneAutoConfiguration(ControlPlaneProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Bean(name = "managerControlPlaneTopologyDescriptor")
    @ConditionalOnMissingBean(name = "managerControlPlaneTopologyDescriptor")
    ControlPlaneTopologyDescriptor managerControlPlaneTopologyDescriptor() {
        String role = requireText(properties.getManager().getRole(), "pockethive.control-plane.manager.role");
        ControlPlaneTopologySettings settings = managerTopologySettings();
        return ControlPlaneTopologyDescriptorFactory.forManagerRole(role, settings);
    }

    @Bean(name = "managerControlPlaneIdentity")
    @ConditionalOnMissingBean(name = "managerControlPlaneIdentity")
    ControlPlaneIdentity managerControlPlaneIdentity(
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor) {
        ControlPlaneProperties.IdentityProperties identity = properties.getIdentity();
        String swarmId = requireText(identity.getSwarmId(), "pockethive.control-plane.swarm-id");
        String instanceId = requireText(identity.getInstanceId(), "pockethive.control-plane.instance-id");
        return new ControlPlaneIdentity(swarmId, descriptor.role(), instanceId);
    }

    @Bean(name = "managerControlPlaneDeclarables")
    @ConditionalOnMissingBean(name = "managerControlPlaneDeclarables")
    Declarables managerControlPlaneDeclarables(
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity,
        ControlPlaneTopologyDeclarableFactory factory,
        TopicExchange controlPlaneExchange) {
        if (!properties.isDeclareTopology() || !properties.getManager().isDeclareTopology()) {
            return new Declarables(List.of());
        }
        return factory.create(descriptor, identity, controlPlaneExchange, controlPlaneExchange);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ControlPlanePublisher.class)
    ManagerControlPlane managerControlPlane(ObjectMapper objectMapper,
        ControlPlanePublisher publisher,
        @Qualifier("managerControlPlaneIdentity") ObjectProvider<ControlPlaneIdentity> identityProvider) {
        ManagerControlPlane.Builder builder = ManagerControlPlane.builder(publisher, objectMapper);
        ControlPlaneProperties.ManagerProperties manager = properties.getManager();
        if (manager.isListenerEnabled()) {
            ControlPlaneIdentity identity = identityProvider.getIfAvailable();
            if (identity == null) {
                throw new IllegalStateException("Manager control-plane identity is required when listener is enabled");
            }
            builder.identity(identity);
            if (manager.isSkipSelfSignals()) {
                builder.selfFilter(SelfFilter.skipSelfInstance());
            }
            ControlPlaneProperties.DuplicateCacheProperties duplicate = manager.getDuplicateCache();
            if (duplicate.isEnabled()) {
                builder.duplicateCache(duplicate.getTtl(), duplicate.getCapacity());
            }
        }
        return builder.build();
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be null or blank");
        }
        return value;
    }

    private ControlPlaneTopologySettings managerTopologySettings() {
        String swarmId = requireText(properties.getSwarmId(), "pockethive.control-plane.swarm-id");
        String controlQueuePrefix = requireText(properties.getControlQueuePrefix(),
            "pockethive.control-plane.control-queue-prefix");
        return new ControlPlaneTopologySettings(swarmId, controlQueuePrefix, Map.of());
    }
}
