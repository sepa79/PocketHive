package io.pockethive.auth.service.oauth;

import java.util.List;

/**
 * Responsibility: Define the versioned file envelope for persisted dynamic OAuth clients.
 * Must not: Interpret OAuth metadata, own registry state, or infer unsupported schema versions.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */
record DynamicClientStateDocument(int schemaVersion, List<DynamicClientStateEntry> clients) {
    DynamicClientStateDocument {
        clients = clients == null ? null : List.copyOf(clients);
    }
}
