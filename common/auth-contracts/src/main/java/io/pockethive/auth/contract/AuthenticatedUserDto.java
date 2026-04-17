package io.pockethive.auth.contract;

import java.util.List;
import java.util.UUID;

public record AuthenticatedUserDto(
    UUID id,
    String username,
    String displayName,
    boolean active,
    AuthProvider authProvider,
    List<AuthGrantDto> grants
) {
    public AuthenticatedUserDto {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        username = requireText(username, "username");
        displayName = requireText(displayName, "displayName");
        if (authProvider == null) {
            throw new IllegalArgumentException("authProvider must not be null");
        }
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
        return value.trim();
    }
}
