package io.pockethive.artemis.work;

import io.pockethive.artemis.topology.ArtemisResourceKind;
import io.pockethive.artemis.topology.ArtemisResourceNames;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.artemis.transport.ArtemisManagement;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.ResolvedWorkTopology;
import io.pockethive.topology.work.WorkPlaneResources;
import io.pockethive.topology.work.WorkResourceIdentity;
import io.pockethive.topology.work.WorkResourceObservation;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientSession;

/**
 * Responsibility: apply resolved Artemis resource intent and observe native state through a session opened on first use.
 * Must not: open sessions during construction, construct names, persist manifests or decide swarm operation success.
 * Contract: RESP-ARTEMIS-RESOURCES — docs/architecture/runtime-responsibilities.md#resp-artemis-resources.
 */
public final class ArtemisWorkResources implements WorkPlaneResources {
    private final String connectionIdentity;
    private final ArtemisSessions sessions;
    private ClientSession session;
    private final Set<WorkResourceIdentity> applied = new HashSet<>();

    public ArtemisWorkResources(ArtemisSessions sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        connectionIdentity = sessions.connectionIdentity();
    }

    @Override public String connectionIdentity() { return connectionIdentity; }

    @Override public synchronized Set<WorkResourceIdentity> appliedResources() { return Set.copyOf(applied); }

    @Override
    public synchronized void ensure(ResolvedWorkTopology topology) {
        validateTopology(topology);
        var session = session();
        try {
            // Detect incompatible existing queues before making any new declarations.
            for (var channel : topology.channels().values()) {
                var queue = session.queueQuery(SimpleString.of(channel.inputAddress()));
                if (queue.isExists() && (!SimpleString.of(channel.outputAddress()).equals(queue.getAddress())
                    || queue.getRoutingType() != RoutingType.ANYCAST || !queue.isDurable())) {
                    throw new IllegalArgumentException("Existing Artemis queue has different settings: " + channel.inputAddress());
                }
            }
            for (var resource : topology.resources()) {
                if (ArtemisResourceKind.require(resource) == ArtemisResourceKind.ADDRESS
                    && !session.addressQuery(SimpleString.of(resource.name())).isExists()) {
                    session.createAddress(SimpleString.of(resource.name()), RoutingType.ANYCAST, false);
                }
            }
            for (var channel : topology.channels().values()) {
                if (!session.queueQuery(SimpleString.of(channel.inputAddress())).isExists()) {
                    applied.remove(channel.resource());
                    session.createQueue(QueueConfiguration.of(channel.inputAddress())
                        .setAddress(channel.outputAddress()).setRoutingType(RoutingType.ANYCAST)
                        .setDurable(true).setAutoCreateAddress(false).setAutoDelete(false));
                }
                applied.add(channel.resource());
            }
        } catch (ActiveMQException failure) {
            throw new IllegalStateException("Cannot ensure Artemis Work resources", failure);
        }
    }

    @Override
    public synchronized Optional<WorkResourceObservation> observe(WorkResourceIdentity resource) {
        var kind = ArtemisResourceKind.require(resource);
        var session = session();
        try {
            if (kind == ArtemisResourceKind.ADDRESS) {
                return session.addressQuery(SimpleString.of(resource.name())).isExists()
                    ? Optional.of(new WorkResourceObservation(0, 0, OptionalLong.empty())) : Optional.empty();
            }
            var queue = session.queueQuery(SimpleString.of(resource.name()));
            return queue.isExists()
                ? Optional.of(new WorkResourceObservation(queue.getMessageCount(), queue.getConsumerCount(), OptionalLong.empty()))
                : Optional.empty();
        } catch (ActiveMQException failure) {
            throw new IllegalStateException("Cannot observe Artemis Work resource", failure);
        }
    }

    @Override
    public Optional<WorkResourceObservation> observeInput(String inputAddress) {
        return observe(ArtemisResourceKind.QUEUE.identity(inputAddress));
    }

    @Override
    public synchronized void remove(WorkResourceIdentity resource) {
        var kind = ArtemisResourceKind.require(resource);
        var session = session();
        try {
            if (kind == ArtemisResourceKind.QUEUE) {
                if (session.queueQuery(SimpleString.of(resource.name())).isExists()) {
                    session.deleteQueue(SimpleString.of(resource.name()));
                }
            } else if (session.addressQuery(SimpleString.of(resource.name())).isExists()) {
                new ArtemisManagement(session, sessions.callTimeoutMillis()).deleteAddress(resource.name());
            }
            applied.remove(resource);
        } catch (ActiveMQException failure) {
            throw new IllegalStateException("Cannot remove Artemis Work resource", failure);
        }
    }

    @Override
    public RemoveResource removalTarget(WorkResourceIdentity resource) {
        return new RemoveResource(RemoveResourceType.WORK_RESOURCE,
            ArtemisResourceNames.resourceAddress(resource), ResourcePlane.WORK);
    }

    @Override
    public WorkResourceIdentity identify(RemoveResource target) {
        if (target.plane() != ResourcePlane.WORK || target.type() != RemoveResourceType.WORK_RESOURCE) {
            throw new IllegalArgumentException("Expected WORK_RESOURCE in WORK plane");
        }
        return ArtemisResourceNames.identify(target.id());
    }

    // All callers serialize resource operations on this owner; the connection owner controls terminal close.
    private ClientSession session() {
        if (session == null) session = sessions.open();
        return session;
    }

    private static void validateTopology(ResolvedWorkTopology topology) {
        Objects.requireNonNull(topology, "topology");
        topology.resources().forEach(ArtemisResourceKind::require);
        var expected = new HashSet<WorkResourceIdentity>();
        for (var channel : topology.channels().values()) {
            if (ArtemisResourceKind.require(channel.resource()) != ArtemisResourceKind.QUEUE
                || !channel.resource().name().equals(channel.inputAddress())) {
                throw new IllegalArgumentException("Artemis channel must identify its input queue");
            }
            if (!expected.add(channel.resource())) {
                throw new IllegalArgumentException("Artemis channels cannot own the same queue twice");
            }
            expected.add(ArtemisResourceKind.ADDRESS.identity(channel.outputAddress()));
        }
        if (!expected.equals(new HashSet<>(topology.resources())) || expected.size() != topology.resources().size()) {
            throw new IllegalArgumentException("Artemis resources must match the resolved channel addresses and queues");
        }
    }
}
