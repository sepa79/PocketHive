package io.pockethive.auth.service.service;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.UserUpsertRequestDto;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.domain.StoredUser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InMemoryUserStore {
    private final Map<UUID, StoredUser> usersById = new LinkedHashMap<>();
    private final Map<String, UUID> idsByUsername = new LinkedHashMap<>();

    public InMemoryUserStore(AuthServiceProperties properties) {
        if (properties.getUsers() == null || properties.getUsers().isEmpty()) {
            throw new IllegalStateException("pockethive.auth-service.users must not be empty");
        }
        for (AuthServiceProperties.UserConfig user : properties.getUsers()) {
            StoredUser stored = toStoredUser(user);
            putInternal(stored, false);
        }
    }

    public synchronized List<StoredUser> listUsers() {
        return usersById.values().stream()
            .sorted(Comparator.comparing(StoredUser::username))
            .toList();
    }

    public synchronized Optional<StoredUser> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        UUID id = idsByUsername.get(username.trim());
        return id == null ? Optional.empty() : Optional.ofNullable(usersById.get(id));
    }

    public synchronized StoredUser requireById(UUID userId) {
        StoredUser user = usersById.get(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return user;
    }

    public synchronized StoredUser upsertUser(UUID userId, UserUpsertRequestDto request) {
        StoredUser existing = usersById.get(userId);
        StoredUser updated = new StoredUser(
            userId,
            request.username(),
            request.displayName(),
            request.active(),
            existing == null ? List.of() : existing.grants());
        putInternal(updated, true);
        return updated;
    }

    public synchronized StoredUser replaceGrants(UUID userId, List<AuthGrantDto> grants) {
        StoredUser existing = requireById(userId);
        StoredUser updated = new StoredUser(
            existing.id(),
            existing.username(),
            existing.displayName(),
            existing.active(),
            grants == null ? List.of() : new ArrayList<>(grants));
        putInternal(updated, true);
        return updated;
    }

    private void putInternal(StoredUser user, boolean replacing) {
        UUID existingId = idsByUsername.get(user.username());
        if (existingId != null && !existingId.equals(user.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        if (replacing) {
            StoredUser previous = usersById.get(user.id());
            if (previous != null && !previous.username().equals(user.username())) {
                idsByUsername.remove(previous.username());
            }
        }
        usersById.put(user.id(), user);
        idsByUsername.put(user.username(), user.id());
    }

    private static StoredUser toStoredUser(AuthServiceProperties.UserConfig user) {
        if (user.getId() == null) {
            throw new IllegalStateException("pockethive.auth-service.users[].id must not be null");
        }
        return new StoredUser(
            user.getId(),
            user.getUsername(),
            user.getDisplayName(),
            user.isActive(),
            user.getGrants());
    }
}
