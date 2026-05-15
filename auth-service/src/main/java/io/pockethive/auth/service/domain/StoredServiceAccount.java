package io.pockethive.auth.service.domain;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.AuthProvider;
import java.util.List;
import java.util.UUID;

public record StoredServiceAccount(
    UUID id,
    String serviceName,
    String displayName,
    String secret,
    boolean active,
    List<AuthGrantDto> grants
) {
    public StoredServiceAccount {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be null or blank");
        }
        serviceName = serviceName.trim();
        displayName = displayName.trim();
        secret = secret.trim();
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    public AuthenticatedUserDto toDto(AuthProvider provider) {
        return new AuthenticatedUserDto(id, serviceName, displayName, active, provider, grants);
    }
}
