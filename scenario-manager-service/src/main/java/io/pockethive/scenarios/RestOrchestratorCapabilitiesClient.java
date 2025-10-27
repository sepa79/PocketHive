package io.pockethive.scenarios;

import org.springframework.web.client.RestClient;

public class RestOrchestratorCapabilitiesClient implements OrchestratorCapabilitiesClient {
    private final RestClient restClient;

    public RestOrchestratorCapabilitiesClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public OrchestratorRuntimeResponse fetchRuntimeCatalogue() {
        OrchestratorRuntimeResponse response = restClient
                .get()
                .uri("/api/capabilities/runtime")
                .retrieve()
                .body(OrchestratorRuntimeResponse.class);
        return response != null ? response : new OrchestratorRuntimeResponse(null);
    }
}
