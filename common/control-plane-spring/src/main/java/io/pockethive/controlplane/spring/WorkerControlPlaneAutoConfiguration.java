package io.pockethive.controlplane.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.consumer.SelfFilter;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.QueueDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Auto-configuration that wires control-plane infrastructure for worker services.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(WorkerControlPlane.class)
@ConditionalOnProperty(prefix = "pockethive.control-plane.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WorkerControlPlaneProperties.class)
public class WorkerControlPlaneAutoConfiguration {

    private final WorkerControlPlaneProperties properties;
    private final Binder binder;

    WorkerControlPlaneAutoConfiguration(WorkerControlPlaneProperties properties,
                                        ConfigurableEnvironment environment) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.binder = Binder.get(environment);
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
    Declarables workerControlPlaneDeclarables(
        @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
        ControlPlaneTopologyDeclarableFactory factory,
        TopicExchange controlPlaneExchange) {
        if (!properties.isDeclareTopology() || !properties.getWorker().isDeclareTopology()) {
            return new Declarables(List.of());
        }
        TopicExchange trafficExchange = ExchangeBuilder
            .topicExchange(resolveTrafficExchange())
            .durable(true)
            .build();
        return factory.create(descriptor, identity, controlPlaneExchange, trafficExchange);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pockethive.control-plane.worker.listener", name = "enabled", havingValue = "true", matchIfMissing = true)
    WorkerControlPlane workerControlPlane(ObjectMapper objectMapper,
        @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity) {
        WorkerControlPlane.Builder builder = WorkerControlPlane.builder(objectMapper).identity(identity);
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
        return ControlPlaneEmitter.using(descriptor, role, publisher);
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
        Map<String, QueueDescriptor> trafficQueues = new LinkedHashMap<>();
        String queue = resolveRabbitInputQueue();
        if (queue != null) {
            trafficQueues.put(role, new QueueDescriptor(queue, Set.of()));
        }
        return new ControlPlaneTopologySettings(swarmId, controlQueuePrefix, trafficQueues);
    }

    private String resolveRabbitInputQueue() {
        return binder.bind("pockethive.inputs.rabbit.queue", String.class).orElse(null);
    }

    private String resolveTrafficExchange() {
        return binder.bind("pockethive.outputs.rabbit.exchange", String.class)
            .orElseThrow(() -> new IllegalStateException(
                "pockethive.outputs.rabbit.exchange must be configured to declare worker queues"));
    }
}
