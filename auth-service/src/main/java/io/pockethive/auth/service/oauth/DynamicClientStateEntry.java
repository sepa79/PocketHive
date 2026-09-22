package io.pockethive.auth.service.oauth;

import java.time.Instant;
import java.util.List;

/**
 * Responsibility: Represent one immutable, non-secret dynamic OAuth client persistence record.
 * Must not: Decide OAuth policy, expiry, storage location, or authorization outcomes.
 * Contract: RESP-OAUTH-CLIENT-STATE — docs/architecture/runtime-responsibilities.md#resp-oauth-client-state.
 */
record DynamicClientStateEntry(
    String registrationId,
    String clientId,
    Instant clientIdIssuedAt,
    String clientName,
    List<String> redirectUris,
    List<String> grantTypes,
    List<String> scopes,
    Instant expiresAt
) {
    DynamicClientStateEntry {
        redirectUris = redirectUris == null ? null : List.copyOf(redirectUris);
        grantTypes = grantTypes == null ? null : List.copyOf(grantTypes);
        scopes = scopes == null ? null : List.copyOf(scopes);
    }
}
