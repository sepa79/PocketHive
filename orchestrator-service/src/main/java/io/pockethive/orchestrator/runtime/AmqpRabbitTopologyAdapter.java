package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;
import io.pockethive.rabbit.api.RabbitConnectionSettings;
import io.pockethive.rabbit.api.RabbitResourceBeans;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.WorkPlaneResources;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project CONTROL and selected WORK observations to the current Rabbit cleanup contract.
 * Must not: reconstruct resource identities, access broker clients, infer absence from failure or authorize cleanup.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
@Component
public class AmqpRabbitTopologyAdapter implements RabbitTopologyPort {
    private final RabbitResources control;
    private final WorkPlaneResources work;
    private final RabbitConnectionSettings controlConnection;

    public AmqpRabbitTopologyAdapter(@Qualifier(RabbitResourceBeans.CONTROL) RabbitResources control,
                                    WorkPlaneResources work, RabbitConnectionSettings controlConnection) {
        this.control = Objects.requireNonNull(control, "control");
        this.work = Objects.requireNonNull(work, "work");
        this.controlConnection = Objects.requireNonNull(controlConnection, "controlConnection");
    }

    @Override public String connectionIdentity(ResourcePlane plane) {
        return requirePlane(plane) == ResourcePlane.WORK ? work.connectionIdentity() : controlConnection.identity();
    }

    @Override public Optional<RabbitQueueResource> queue(ResourcePlane plane, String name) {
        if (requirePlane(plane) == ResourcePlane.WORK) {
            return work.observe(work.identify(new RemoveResource(RemoveResourceType.RABBIT_QUEUE, name, plane)))
                .map(queue -> new RabbitQueueResource(name, queue.messages(), queue.consumers()));
        }
        return control.queue(name).map(queue -> new RabbitQueueResource(name, queue.messages(), queue.consumers()));
    }

    @Override public Optional<RabbitExchangeResource> exchange(ResourcePlane plane, String name) {
        boolean exists = requirePlane(plane) == ResourcePlane.WORK
            ? work.observe(work.identify(new RemoveResource(RemoveResourceType.RABBIT_EXCHANGE, name, plane))).isPresent()
            : control.exchangeExists(name);
        return exists ? Optional.of(new RabbitExchangeResource(name)) : Optional.empty();
    }

    @Override public void deleteQueue(ResourcePlane plane, String name) {
        if (requirePlane(plane) == ResourcePlane.WORK) {
            work.remove(work.identify(new RemoveResource(RemoveResourceType.RABBIT_QUEUE, name, plane)));
        } else {
            control.deleteQueue(name);
        }
    }

    @Override public void deleteExchange(ResourcePlane plane, String name) {
        if (requirePlane(plane) == ResourcePlane.WORK) {
            work.remove(work.identify(new RemoveResource(RemoveResourceType.RABBIT_EXCHANGE, name, plane)));
        } else {
            control.deleteExchange(name);
        }
    }

    private static ResourcePlane requirePlane(ResourcePlane plane) {
        return Objects.requireNonNull(plane, "plane").requireRabbit();
    }
}
