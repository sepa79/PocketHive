package io.pockethive.auth.service.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Responsibility: Carry the RFC 7591 public-client registration response contract.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

record DynamicClientRegistrationResponse(
    @JsonProperty("client_id") String clientId,
    @JsonProperty("client_id_issued_at") long clientIdIssuedAt,
    @JsonProperty("client_name") String clientName,
    @JsonProperty("redirect_uris") List<String> redirectUris,
    @JsonProperty("grant_types") List<String> grantTypes,
    @JsonProperty("response_types") List<String> responseTypes,
    @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
    String scope
) {
}
