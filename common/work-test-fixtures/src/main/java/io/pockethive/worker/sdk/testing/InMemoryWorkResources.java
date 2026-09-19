package io.pockethive.worker.sdk.testing;

import io.pockethive.topology.work.*;
import io.pockethive.swarm.model.lifecycle.*;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Responsibility: adapt resource operations and observations to the one memory transport state.
 * Must not: keep another resource inventory or infer successful deletion.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class InMemoryWorkResources implements WorkPlaneResources {
    private static final String OWNER = "memory";
    private static final String KIND = "channel";
    private final InMemoryWorkTransport transport;
    private final String instance;
    public InMemoryWorkResources(InMemoryWorkTransport transport, String instance) {
        this.transport = Objects.requireNonNull(transport);
        if (instance == null || instance.isBlank()) throw new IllegalArgumentException("Explicit memory instance required");
        this.instance = instance;
    }
    static WorkResourceIdentity identity(String address) {
        return new WorkResourceIdentity(OWNER, KIND, InMemoryWorkAddress.require(address));
    }
    private static String address(WorkResourceIdentity resource) {
        if (!OWNER.equals(resource.owner()) || !KIND.equals(resource.kind())) throw new IllegalArgumentException("Foreign Work resource");
        return InMemoryWorkAddress.require(resource.name());
    }
    @Override public String connectionIdentity() { return OWNER + ":" + instance; }
    @Override public Set<WorkResourceIdentity> appliedResources() {
        return transport.addresses().stream().map(InMemoryWorkResources::identity).collect(Collectors.toUnmodifiableSet());
    }
    @Override public synchronized void ensure(ResolvedWorkTopology topology) {
        var addresses = topology.resources().stream().map(InMemoryWorkResources::address).toList();
        for (String address : addresses) if (!transport.exists(address)) transport.create(address);
    }
    @Override public Optional<WorkResourceObservation> observe(WorkResourceIdentity resource) {
        return transport.observe(address(resource));
    }
    @Override public Optional<WorkResourceObservation> observeInput(String inputAddress) {
        return transport.observe(InMemoryWorkAddress.require(inputAddress));
    }
    @Override public synchronized void remove(WorkResourceIdentity resource) {
        String address = address(resource);
        if (transport.exists(address)) transport.remove(address);
    }
    @Override public RemoveResource removalTarget(WorkResourceIdentity resource) {
        return new RemoveResource(RemoveResourceType.WORK_RESOURCE, address(resource), ResourcePlane.WORK);
    }
    @Override public WorkResourceIdentity identify(RemoveResource resource) {
        if (resource.type() != RemoveResourceType.WORK_RESOURCE || resource.plane() != ResourcePlane.WORK) {
            throw new IllegalArgumentException("Expected native WORK resource");
        }
        return identity(resource.id());
    }
}
