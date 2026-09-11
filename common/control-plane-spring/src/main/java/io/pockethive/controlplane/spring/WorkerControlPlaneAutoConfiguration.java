package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.consumer.SelfFilter;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.pockethive.rabbit.api.RabbitTopologySpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that wires control-plane infrastructure for worker services.
 * <p>
 * Responsibility: compose worker Control Plane identity, listener and declarations.
 * Must not: read Work settings or declare Work resources.
 * Contract: RESP-CP-COMPOSITION — docs/architecture/runtime-responsibilities.md#resp-cp-composition.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(WorkerControlPlane.class)
@ConditionalOnProperty(prefix = "pockethive.control-plane.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WorkerControlPlaneProperties.class)
public class WorkerControlPlaneAutoConfiguration {

    private final WorkerControlPlaneProperties properties;

    WorkerControlPlaneAutoConfiguration(WorkerControlPlaneProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Bean(name = "workerControlPlaneTopologyDescriptor")
    @ConditionalOnMissingBean(name = "workerControlPlaneTopologyDescriptor")
    ControlPlaneTopologyDescriptor workerControlPlaneTopologyDescriptor() {
        String role = requireText(properties.getWorker().getRole(), "pockethive.control-plane.worker.role");
        ControlPlaneTopologySettings settings = workerTopologySettings(role);
        return ControlPlaneTopologyDescriptorFactory.forWorkerRole(role, settings);
    }

    @Bean(name = "workerControlPlaneIdentity")
    @ConditionalOnMissingBean(name = "workerControlPlaneIdentity")
    ControlPlaneIdentity workerControlPlaneIdentity(
        @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor) {
        String swarmId = requireText(properties.getSwarmId(), "pockethive.control-plane.swarm-id");
        String instanceId = requireText(properties.getInstanceId(), "pockethive.control-plane.instance-id");
        return new ControlPlaneIdentity(swarmId, descriptor.role(), instanceId);
    }

    @Bean(name = "workerControlPlaneDeclarables")
    @ConditionalOnMissingBean(name = "workerControlPlaneDeclarables")
    RabbitTopologySpec workerControlPlaneDeclarables(
        @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        ControlPlaneTopologyDeclarableFactory factory,
        RabbitExchangeSpec controlPlaneExchange) {
        if (!properties.isDeclareTopology() || !properties.getWorker().isDeclareTopology()) {
            return new RabbitTopologySpec(List.of(), List.of());
        }
        return factory.create(descriptor, identity, controlPlaneExchange);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pockethive.control-plane.worker.listener", name = "enabled", havingValue = "true", matchIfMissing = true)
    WorkerControlPlane workerControlPlane(ControlPlaneCodec codec,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity) {
        WorkerControlPlane.Builder builder = WorkerControlPlane.builder(codec).identity(identity);
        WorkerControlPlaneProperties.Worker worker = properties.getWorker();
        if (worker.isSkipSelfSignals()) {
            builder.selfFilter(SelfFilter.skipSelfInstance());
        }
        WorkerControlPlaneProperties.Worker.DuplicateCache duplicate = worker.getDuplicateCache();
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
        return ControlPlaneEmitter.using(descriptor, role, publisher, runtimeMeta());
    }

    private static Map<String, Object> runtimeMeta() {
        String templateId = requireText(trimToNull(System.getenv("POCKETHIVE_TEMPLATE_ID")), "POCKETHIVE_TEMPLATE_ID");
        String runId = requireText(trimToNull(System.getenv("POCKETHIVE_JOURNAL_RUN_ID")), "POCKETHIVE_JOURNAL_RUN_ID");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("templateId", templateId);
        meta.put("runId", runId);
        String containerId = trimToNull(System.getenv("HOSTNAME"));
        if (containerId != null) {
            meta.put("containerId", containerId);
        }
        String image = trimToNull(System.getenv("POCKETHIVE_RUNTIME_IMAGE"));
        if (image != null) {
            meta.put("image", image);
        }
        String stackName = trimToNull(System.getenv("POCKETHIVE_RUNTIME_STACK_NAME"));
        if (stackName != null) {
            meta.put("stackName", stackName);
        }
        return Map.copyOf(meta);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Bean(name = "workerControlQueueName")
    @ConditionalOnMissingBean(name = "workerControlQueueName")
    String workerControlQueueName(
        @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity
    ) {
        return descriptor.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow(() -> new IllegalStateException(
                "Control queue descriptor is missing for worker role " + descriptor.role()));
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be null or blank");
        }
        return value;
    }

    private ControlPlaneTopologySettings workerTopologySettings(String role) {
        String swarmId = requireText(properties.getSwarmId(), "pockethive.control-plane.swarm-id");
        String controlQueuePrefix = requireText(properties.getControlQueuePrefix(),
            "pockethive.control-plane.control-queue-prefix");
        return new ControlPlaneTopologySettings(swarmId, controlQueuePrefix, Map.of());
    }

}
