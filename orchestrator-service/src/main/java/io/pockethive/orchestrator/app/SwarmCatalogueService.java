package io.pockethive.orchestrator.app;

import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: forget an explicitly selected catalogue entry after verifying compute absence.
 * Must not: delete runtime resources, infer absence from STALE or publish a REMOVE outcome.
 * Contract: RESP-SWARM-CATALOGUE-REMOVAL — docs/architecture/runtime-responsibilities.md#resp-swarm-catalogue-removal.
 */
@Service
public final class SwarmCatalogueService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SwarmCatalogueService.class);
    private static final String CATALOGUE_FORGOTTEN = "swarm-catalogue-forgotten";
    private final SwarmStore store;
    private final SwarmOperationCoordinator operations;
    private final ComputeRuntimeInventoryPort inventory;
    private final HiveJournal journal;

    public SwarmCatalogueService(SwarmStore store, SwarmOperationCoordinator operations,
                                 ComputeRuntimeInventoryPort inventory, HiveJournal journal) {
        this.store = store;
        this.operations = operations;
        this.inventory = inventory;
        this.journal = journal;
    }

    public void forget(Swarm expected) {
        List<ComputeRuntimeResource> resources;
        try {
            resources = java.util.Objects.requireNonNull(inventory.list(), "compute inventory");
        } catch (RuntimeException failure) {
            throw new CatalogueInventoryUnavailableException(failure);
        }
        if (resources.stream().anyMatch(resource ->
            expected.getContainerId().equals(resource.runtimeId())
                || expected.getId().equals(resource.labels().get(PocketHiveDockerLabels.SWARM_ID)))) {
            throw new IllegalStateException("Compute resources still exist for swarm " + expected.getId());
        }
        // Same admission monitor as operation reservation; local mutation only under both monitors.
        synchronized (operations) {
            if (operations.activeLifecycle(expected.getId()).isPresent()) {
                throw new IllegalStateException("Swarm has an active lifecycle operation");
            }
            if (!store.updateIfCurrent(expected, () -> store.remove(expected.getId()))) {
                throw new IllegalStateException("Swarm catalogue entry changed during inventory verification");
            }
        }
        try {
            journal.appendDurably(expected.getRunId(), HiveJournalEntry.info(expected.getId(), HiveJournal.Direction.LOCAL,
            CATALOGUE_FORGOTTEN, CATALOGUE_FORGOTTEN, ControlPlaneRoles.ORCHESTRATOR,
            ControlScope.forInstance(expected.getId(), ControlPlaneRoles.SWARM_CONTROLLER, expected.getInstanceId()),
            null, null, null, Map.of("runId", expected.getRunId(), "controllerInstance", expected.getInstanceId(),
                "controllerRuntimeId", expected.getContainerId()), null, null));
        } catch (RuntimeException failure) {
            // The catalogue mutation is complete; observability failure cannot turn it into a conflict.
            log.error("Catalogue entry forgotten but journal append failed: swarm={} run={} controller={}",
                expected.getId(), expected.getRunId(), expected.getInstanceId(), failure);
        }
    }
}
