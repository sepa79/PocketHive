package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import io.pockethive.rabbit.api.RabbitTopologySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: project canonical Control Plane topology into Rabbit public resource specifications.
 * Must not: implement broker declarations, physical naming or Work topology.
 * Contract: RESP-CP-DECLARATIONS — docs/architecture/runtime-responsibilities.md#resp-cp-declarations.
 */
public final class ControlPlaneTopologyDeclarableFactory {
    public RabbitTopologySpec create(ControlPlaneTopologyDescriptor descriptor,
                                     ControlPlaneIdentity identity, RabbitExchangeSpec exchange) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(exchange, "exchange");
        String instanceId = Objects.requireNonNull(identity, "identity").instanceId();
        if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("identity.instanceId must not be blank");
        var queues = new ArrayList<RabbitQueueSpec>();
        var bindings = new ArrayList<RabbitBindingSpec>();
        descriptor.controlQueue(instanceId).ifPresent(queue -> add(queue.name(), queue.allBindings(), exchange, queues, bindings));
        if (!ControlPlaneTopologyDescriptorFactory.isWorkerRole(descriptor.role())) {
            descriptor.additionalQueues(instanceId).forEach(queue -> add(queue.name(), queue.bindings(), exchange, queues, bindings));
        }
        return new RabbitTopologySpec(queues, bindings);
    }

    private static void add(String name, java.util.Collection<String> keys, RabbitExchangeSpec exchange,
                            List<RabbitQueueSpec> queues, List<RabbitBindingSpec> bindings) {
        queues.add(new RabbitQueueSpec(name, true, false, false, Map.of()));
        keys.stream().filter(key -> key != null && !key.isBlank())
            .map(key -> new RabbitBindingSpec(name, exchange.name(), key, Map.of())).forEach(bindings::add);
    }
}
