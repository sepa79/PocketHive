package io.pockethive.orchestrator.app;

/** Infrastructure absence could not be established; no catalogue mutation is permitted. */
public final class CatalogueInventoryUnavailableException extends RuntimeException {
    public CatalogueInventoryUnavailableException(RuntimeException cause) {
        super("Compute inventory is unavailable; catalogue entry retained", cause);
    }
}
