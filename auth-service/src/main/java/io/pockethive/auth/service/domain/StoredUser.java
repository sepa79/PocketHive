package io.pockethive.auth.service.domain;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProvider;
import java.util.List;
import java.util.UUID;

public record StoredUser(
    UUID id,
    String username,
    String displayName,
    boolean active,
    List<AuthGrantDto> grants
) {
    public StoredUser {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }
        username = username.trim();
        displayName = displayName.trim();
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    public AuthenticatedUserDto toDto(AuthProvider provider) {
        return new AuthenticatedUserDto(id, username, displayName, active, provider, grants);
    }
}
