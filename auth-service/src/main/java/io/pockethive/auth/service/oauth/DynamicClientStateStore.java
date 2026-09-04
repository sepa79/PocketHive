package io.pockethive.auth.service.oauth;

import java.util.List;

/**
 * Responsibility: Persist and load the complete durable projection of dynamic OAuth client state.
 * Must not: Validate OAuth policy, own client expiry, or persist authorization codes, tokens, or consent.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */
interface DynamicClientStateStore {
    List<DynamicClientStateEntry> load();

    void replace(List<DynamicClientStateEntry> clients);
}
