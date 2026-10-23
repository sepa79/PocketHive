package io.pockethive.auth.service.oauth;

import io.pockethive.auth.service.domain.StoredUser;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: resolve immutable OAuth subjects to the canonical current active user.
 * Must not: resolve usernames, persist authorization state or decide scopes.
 * Contract: RESP-OAUTH-PRINCIPAL-IDENTITY — docs/architecture/runtime-responsibilities.md#resp-oauth-principal-identity.
 */
public final class OAuthPrincipalLookup {
    private final InMemoryUserStore users;

    public OAuthPrincipalLookup(InMemoryUserStore users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    public Optional<StoredUser> findActive(String subject) {
        if (subject == null) return Optional.empty();
        UUID id;
        try {
            id = UUID.fromString(subject);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
        if (!id.toString().equals(subject)) return Optional.empty();
        StoredUser user;
        try {
            user = users.requireById(id);
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().equals(HttpStatus.NOT_FOUND)) return Optional.empty();
            throw error;
        }
        return user.active() ? Optional.of(user) : Optional.empty();
    }
}
