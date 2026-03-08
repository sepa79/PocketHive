package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkBindingClearRequest(@NotBlank String sutId,
                                         @NotBlank String requestedBy,
                                         String reason) {

    public NetworkBindingClearRequest {
        sutId = requireText(sutId, "sutId");
        requestedBy = requireText(requestedBy, "requestedBy");
        reason = trimToNull(reason);
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
