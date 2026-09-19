package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.*;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
/**
 * Responsibility: apply resolved Work resource intent through the sole Rabbit resource API.
 * Must not: resolve names, own swarm state, borrow CONTROL resources or infer removal success from attempts.
 * Contract: RESP-RABBIT-RESOURCES — docs/architecture/runtime-responsibilities.md#resp-rabbit-resources.
 */
public final class RabbitWorkResources implements WorkPlaneResources {
    private final RabbitResources resources;
    private final RabbitConnectionSettings connection;
    private final Set<WorkResourceIdentity> declared = new HashSet<>();

    public RabbitWorkResources(RabbitResources resources, RabbitConnectionSettings connection) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override public String connectionIdentity() { return connection.identity(); }

    @Override public RemoveResource removalTarget(WorkResourceIdentity resource) {
        var type = switch (RabbitWorkResourceKind.require(resource)) {
            case QUEUE -> RemoveResourceType.RABBIT_QUEUE;
            case EXCHANGE -> RemoveResourceType.RABBIT_EXCHANGE;
        };
        return new RemoveResource(type, resource.name(),
            ResourcePlane.WORK);
    }

    @Override public WorkResourceIdentity identify(RemoveResource target) {
        if (target.plane() != ResourcePlane.WORK)
            throw new IllegalArgumentException("Expected a WORK resource");
        return switch (target.type()) {
            case RABBIT_QUEUE -> RabbitWorkResourceKind.QUEUE.identity(target.id());
            case RABBIT_EXCHANGE -> RabbitWorkResourceKind.EXCHANGE.identity(target.id());
            default -> throw new IllegalArgumentException("Resource is not supported by Rabbit WORK: " + target.type());
        };
    }

    @Override public synchronized Set<WorkResourceIdentity> appliedResources() { return Set.copyOf(declared); }

    @Override public synchronized void ensure(ResolvedWorkTopology topology) {
        topology.resources().forEach(RabbitWorkResourceKind::require);
        var exchanges = topology.resources().stream()
            .filter(resource -> RabbitWorkResourceKind.require(resource) == RabbitWorkResourceKind.EXCHANGE).toList();
        if (exchanges.size() != 1) throw new IllegalArgumentException("Exactly one Rabbit Work exchange required");
        String exchange = exchanges.getFirst().name();
        resources.declareExchange(new RabbitExchangeSpec(exchange, true, false, Map.of()));
        for (var channel : topology.channels().values()) {
            var identity = channel.resource();
            String queue = identity.name();
            boolean missing = resources.queue(queue).isEmpty();
            if (missing) declared.remove(identity);
            if (missing || !declared.contains(identity)) {
                resources.declareQueue(new RabbitQueueSpec(queue, true, false, false, Map.of()));
            }
            resources.bind(new RabbitBindingSpec(queue, exchange, channel.outputAddress(), Map.of()));
            declared.add(identity);
        }
    }

    @Override public Optional<WorkResourceObservation> observeInput(String inputAddress) {
        return observe(RabbitWorkResourceKind.QUEUE.identity(inputAddress));
    }

    @Override public Optional<WorkResourceObservation> observe(WorkResourceIdentity resource) {
        return switch (RabbitWorkResourceKind.require(resource)) {
            case QUEUE -> resources.queue(resource.name())
                .map(value -> new WorkResourceObservation(value.messages(), value.consumers(), value.oldestAgeSeconds()));
            case EXCHANGE -> resources.exchangeExists(resource.name())
                ? Optional.of(new WorkResourceObservation(0, 0, OptionalLong.empty())) : Optional.empty();
        };
    }

    @Override public synchronized void remove(WorkResourceIdentity resource) {
        switch (RabbitWorkResourceKind.require(resource)) {
            case QUEUE -> resources.deleteQueue(resource.name());
            case EXCHANGE -> resources.deleteExchange(resource.name());
        }
        declared.remove(resource);
    }
}
