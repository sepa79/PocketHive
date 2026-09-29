package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.auth.OrchestratorCurrentUserHolder;
import io.pockethive.orchestrator.domain.SwarmStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: map the explicit catalogue-only deletion HTTP request.
 * Must not: decide infrastructure absence, mutate the catalogue or duplicate grant policy.
 * Contract: RESP-SWARM-CATALOGUE-REMOVAL — docs/architecture/runtime-responsibilities.md#resp-swarm-catalogue-removal.
 */
@RestController
public final class SwarmCatalogueController {
    private final SwarmStore store;
    private final SwarmAccessService access;
    private final SwarmCatalogueService catalogue;

    public SwarmCatalogueController(SwarmStore store, SwarmAccessService access, SwarmCatalogueService catalogue) {
        this.store = store;
        this.access = access;
        this.catalogue = catalogue;
    }

    @DeleteMapping("/api/swarms/{swarmId}/catalogue-entry")
    public ResponseEntity<Void> delete(@PathVariable String swarmId) {
        var swarm = store.find(swarmId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!access.canManage(OrchestratorCurrentUserHolder.get(), swarm)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        try {
            catalogue.forget(swarm);
        } catch (CatalogueInventoryUnavailableException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage(), failure);
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage(), failure);
        }
        return ResponseEntity.noContent().build();
    }
}
