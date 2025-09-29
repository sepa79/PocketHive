package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.QueueDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Factory that converts control-plane topology descriptors into AMQP declarables.
 */
public final class ControlPlaneTopologyDeclarableFactory {

    public Declarables create(ControlPlaneTopologyDescriptor descriptor,
                              ControlPlaneIdentity identity,
                              TopicExchange exchange) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(exchange, "exchange");
        String instanceId = requireText(identity.instanceId(), "identity.instanceId");
        List<Declarable> declarables = new ArrayList<>();
        descriptor.controlQueue(instanceId).ifPresent(queueDescriptor ->
            declarables.addAll(createControlQueue(queueDescriptor, exchange)));
        Collection<QueueDescriptor> additionalQueues = descriptor.additionalQueues(instanceId);
        for (QueueDescriptor queueDescriptor : additionalQueues) {
            declarables.addAll(createQueue(queueDescriptor, exchange));
        }
        return new Declarables(declarables);
    }

    private Collection<Declarable> createControlQueue(ControlQueueDescriptor descriptor, TopicExchange exchange) {
        Queue queue = QueueBuilder.durable(descriptor.name()).build();
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(queue);
        for (String routingKey : descriptor.allBindings()) {
            if (isText(routingKey)) {
                Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);
                declarables.add(binding);
            }
        }
        return declarables;
    }

    private Collection<Declarable> createQueue(QueueDescriptor descriptor, TopicExchange exchange) {
        Queue queue = QueueBuilder.durable(descriptor.name()).build();
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(queue);
        for (String routingKey : descriptor.bindings()) {
            if (isText(routingKey)) {
                Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);
                declarables.add(binding);
            }
        }
        return declarables;
    }

    private static boolean isText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String name) {
        if (!isText(value)) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value;
    }
}
