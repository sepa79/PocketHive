package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedSutEndpoint(@NotBlank String endpointId,
                                  @NotBlank String kind,
                                  @NotBlank String clientBaseUrl,
                                  @NotBlank String clientAuthority,
                                  @NotBlank String upstreamAuthority) {

    public ResolvedSutEndpoint {
        endpointId = requireText(endpointId, "endpointId");
        kind = requireText(kind, "kind");
        clientBaseUrl = requireText(clientBaseUrl, "clientBaseUrl");
        clientAuthority = requireText(clientAuthority, "clientAuthority");
        upstreamAuthority = requireText(upstreamAuthority, "upstreamAuthority");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
