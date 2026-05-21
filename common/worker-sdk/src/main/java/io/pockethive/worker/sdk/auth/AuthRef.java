package io.pockethive.worker.sdk.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AuthRef(
    String profileId,
    AuthApplyAs applyAs,
    String headerName,
    String queryParam,
    String targetField
) {
    public AuthRef {
        profileId = requireNonBlank(profileId, "authRef.profileId");
        if (applyAs == null) {
            throw new IllegalArgumentException("authRef.applyAs must not be null");
        }
        headerName = normalize(headerName);
        queryParam = normalize(queryParam);
        targetField = normalize(targetField);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
