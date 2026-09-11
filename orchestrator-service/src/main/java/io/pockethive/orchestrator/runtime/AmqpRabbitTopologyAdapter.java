package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;
import io.pockethive.orchestrator.runtime.RabbitTopologyPort;
import io.pockethive.rabbit.api.RabbitResources;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project Rabbit API resource observations for governed runtime cleanup.
 * Must not: access broker clients, decode broker properties or authorize cleanup.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
@Component
public class AmqpRabbitTopologyAdapter implements RabbitTopologyPort {
    private final RabbitResources control;
    private final RabbitResources work;
    private final io.pockethive.rabbit.api.RabbitConnections connections;

    public AmqpRabbitTopologyAdapter(
        @org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.CONTROL) RabbitResources control,
        @org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.WORK) RabbitResources work, io.pockethive.rabbit.api.RabbitConnections connections) {
        this.control = Objects.requireNonNull(control, "control");
        this.work = Objects.requireNonNull(work, "work");
        this.connections = Objects.requireNonNull(connections, "connections");
    }
    @Override public String connectionIdentity(io.pockethive.swarm.model.lifecycle.ResourcePlane plane) {
        return switch (Objects.requireNonNull(plane, "plane").requireRabbit()) {
            case CONTROL -> connections.control().identity();
            case WORK -> connections.work().identity();
            case NONE -> throw new IllegalArgumentException("Rabbit resources require a plane");
        };
    }

    private RabbitResources resources(io.pockethive.swarm.model.lifecycle.ResourcePlane plane) {
        return switch (Objects.requireNonNull(plane, "plane").requireRabbit()) {
            case CONTROL -> control;
            case WORK -> work;
            case NONE -> throw new IllegalArgumentException("Rabbit resources require a plane");
        };
    }

    @Override public Optional<RabbitQueueResource> queue(io.pockethive.swarm.model.lifecycle.ResourcePlane plane, String name) {
        return resources(plane).queue(name).map(queue -> new RabbitQueueResource(name, queue.messages(), queue.consumers()));
    }
    @Override public Optional<RabbitExchangeResource> exchange(io.pockethive.swarm.model.lifecycle.ResourcePlane plane, String name) {
        return resources(plane).exchangeExists(name) ? Optional.of(new RabbitExchangeResource(name)) : Optional.empty();
    }
    @Override public void deleteQueue(io.pockethive.swarm.model.lifecycle.ResourcePlane plane, String name) { resources(plane).deleteQueue(name); }
    @Override public void deleteExchange(io.pockethive.swarm.model.lifecycle.ResourcePlane plane, String name) { resources(plane).deleteExchange(name); }
}
