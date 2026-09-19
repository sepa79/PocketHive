package io.pockethive.topology.work;

import io.pockethive.swarm.model.lifecycle.RemoveResource;
import java.util.Optional;
/**
 * Responsibility: expose selected Work resource operations and observations.
 * Must not: choose an adapter, reconstruct consumer-specific names or decide swarm lifecycle outcomes.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public interface WorkPlaneResources {
    String connectionIdentity();
    java.util.Set<WorkResourceIdentity> appliedResources();
    void ensure(ResolvedWorkTopology topology);
    Optional<WorkResourceObservation> observe(WorkResourceIdentity resource);
    Optional<WorkResourceObservation> observeInput(String inputAddress);
    void remove(WorkResourceIdentity resource);
    RemoveResource removalTarget(WorkResourceIdentity resource);
    WorkResourceIdentity identify(RemoveResource target);
}
