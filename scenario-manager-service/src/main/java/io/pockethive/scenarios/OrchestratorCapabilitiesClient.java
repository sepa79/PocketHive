package io.pockethive.scenarios;

import java.util.Map;

public interface OrchestratorCapabilitiesClient {
    OrchestratorRuntimeResponse fetchRuntimeCatalogue();

    record OrchestratorRuntimeResponse(Map<String, Object> catalogue) {}
}
