package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkBindingRequest(@NotBlank String sutId,
                                    NetworkMode networkMode,
                                    String networkProfileId,
                                    @NotBlank String requestedBy,
                                    String reason,
                                    @Valid ResolvedSutEnvironment resolvedSut) {

    public NetworkBindingRequest {
        sutId = requireText(sutId, "sutId");
        networkMode = NetworkMode.directIfNull(networkMode);
        networkProfileId = trimToNull(networkProfileId);
        requestedBy = requireText(requestedBy, "requestedBy");
        reason = trimToNull(reason);
        if (networkMode == NetworkMode.DIRECT && networkProfileId != null) {
            throw new IllegalArgumentException("networkProfileId requires networkMode=PROXIED");
        }
        if (networkMode == NetworkMode.PROXIED && networkProfileId == null) {
            throw new IllegalArgumentException("networkProfileId must be provided when networkMode=PROXIED");
        }
        if (resolvedSut != null && !resolvedSut.sutId().equals(sutId)) {
            throw new IllegalArgumentException("resolvedSut.sutId must match sutId");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
