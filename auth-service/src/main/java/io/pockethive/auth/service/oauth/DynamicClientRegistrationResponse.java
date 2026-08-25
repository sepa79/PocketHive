package io.pockethive.auth.service.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

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
