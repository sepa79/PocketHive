package io.pockethive.controlplane.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.consumer.SelfFilter;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.List;
import java.util.Objects;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that wires control-plane infrastructure for worker services.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(WorkerControlPlane.class)
@ConditionalOnProperty(prefix = "pockethive.control-plane.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerControlPlaneAutoConfiguration {

    private final ControlPlaneProperties properties;

    WorkerControlPlaneAutoConfiguration(ControlPlaneProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Bean(name = "workerControlPlaneTopologyDescriptor")
    @ConditionalOnMissingBean(name = "workerControlPlaneTopologyDescriptor")
    ControlPlaneTopologyDescriptor workerControlPlaneTopologyDescriptor() {
        String role = requireText(properties.getWorker().getRole(), "pockethive.control-plane.worker.role");
        return ControlPlaneTopologyDescriptorFactory.forWorkerRole(role);
    }

    @Bean(name = "workerControlPlaneIdentity")
    @ConditionalOnMissingBean(name = "workerControlPlaneIdentity")
    ControlPlaneIdentity workerControlPlaneIdentity(
        @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor) {
        String swarmId = requireText(properties.resolveSwarmId(properties.getWorker().getSwarmId()),
            "pockethive.control-plane.swarm-id");
        String instanceId = requireText(properties.getWorker().getInstanceId(),
            "pockethive.control-plane.worker.instance-id");
        return new ControlPlaneIdentity(swarmId, descriptor.role(), instanceId);
    }

    @Bean(name = "workerControlPlaneDeclarables")
    @ConditionalOnMissingBean(name = "workerControlPlaneDeclarables")
    Declarables workerControlPlaneDeclarables(
        @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        ControlPlaneTopologyDeclarableFactory factory,
        TopicExchange controlPlaneExchange) {
        if (!properties.isDeclareTopology() || !properties.getWorker().isDeclareTopology()) {
            return new Declarables(List.of());
        }
        return factory.create(descriptor, identity, controlPlaneExchange);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pockethive.control-plane.worker.listener", name = "enabled", havingValue = "true", matchIfMissing = true)
    WorkerControlPlane workerControlPlane(ObjectMapper objectMapper,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity) {
        WorkerControlPlane.Builder builder = WorkerControlPlane.builder(objectMapper).identity(identity);
        ControlPlaneProperties.WorkerProperties worker = properties.getWorker();
        if (worker.isSkipSelfSignals()) {
            builder.selfFilter(SelfFilter.skipSelfInstance());
        }
        ControlPlaneProperties.DuplicateCacheProperties duplicate = worker.getDuplicateCache();
        if (duplicate.isEnabled()) {
            builder.duplicateCache(duplicate.getTtl(), duplicate.getCapacity());
        }
        return builder.build();
    }

    @Bean(name = "workerControlPlaneEmitter")
    @ConditionalOnMissingBean(name = "workerControlPlaneEmitter")
    @ConditionalOnBean(ControlPlanePublisher.class)
    ControlPlaneEmitter workerControlPlaneEmitter(
        @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        ControlPlanePublisher publisher
    ) {
        RoleContext role = RoleContext.fromIdentity(identity);
        return ControlPlaneEmitter.using(descriptor, role, publisher);
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be null or blank");
        }
        return value;
    }
}
