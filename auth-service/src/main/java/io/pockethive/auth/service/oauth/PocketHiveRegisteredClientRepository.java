package io.pockethive.auth.service.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * Responsibility: Own bounded registered-client lookup, renewal, expiry, and dynamic registration state.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

public final class PocketHiveRegisteredClientRepository implements RegisteredClientRepository {
    private final Map<String, RegisteredClient> fixedById;
    private final Map<String, RegisteredClient> fixedByClientId;
    private Map<String, DynamicClient> dynamicById = Map.of();
    private Map<String, DynamicClient> dynamicByClientId = Map.of();
    private final int capacity;
    private final Duration lifetime;
    private final Clock clock;
    private final DynamicClientStateStore stateStore;
    private final TokenSettings tokenSettings;

    PocketHiveRegisteredClientRepository(List<RegisteredClient> fixedClients, int capacity,
                                         Duration lifetime, Clock clock,
                                         DynamicClientStateStore stateStore,
                                         TokenSettings tokenSettings) {
        if (fixedClients == null || fixedClients.isEmpty() || capacity < 1 || lifetime == null
            || lifetime.isZero() || lifetime.isNegative() || clock == null || stateStore == null
            || tokenSettings == null) {
            throw new IllegalArgumentException("OAUTH_CLIENT_REGISTRY_CONFIGURATION_INVALID");
        }
        Map<String, RegisteredClient> byId = new HashMap<>();
        Map<String, RegisteredClient> byClientId = new HashMap<>();
        for (RegisteredClient client : fixedClients) {
            Objects.requireNonNull(client, "fixedClient");
            if (byId.putIfAbsent(client.getId(), client) != null
                || byClientId.putIfAbsent(client.getClientId(), client) != null) {
                throw new IllegalArgumentException("OAUTH_FIXED_CLIENT_IDENTIFIER_DUPLICATE");
            }
        }
        this.fixedById = Map.copyOf(byId);
        this.fixedByClientId = Map.copyOf(byClientId);
        this.capacity = capacity;
        this.lifetime = lifetime;
        this.clock = clock;
        this.stateStore = stateStore;
        this.tokenSettings = tokenSettings;
        restoreDynamicClients();
    }

    @Override
    public synchronized void save(RegisteredClient client) {
        Objects.requireNonNull(client, "registeredClient");
        if (fixedById.containsKey(client.getId()) || fixedByClientId.containsKey(client.getClientId())) {
            throw new IllegalArgumentException("OAUTH_FIXED_CLIENT_IMMUTABLE");
        }
        List<DynamicClient> active = activeClients();
        if (active.stream().anyMatch(dynamic -> dynamic.client().getId().equals(client.getId())
            || dynamic.client().getClientId().equals(client.getClientId()))) {
            throw new IllegalArgumentException("OAUTH_DYNAMIC_CLIENT_IDENTIFIER_DUPLICATE");
        }
        if (active.size() >= capacity) {
            throw new DynamicClientCapacityException();
        }
        active.add(new DynamicClient(client, nextExpiry()));
        replaceDynamicClients(active);
    }

    @Override
    public synchronized RegisteredClient findById(String id) {
        requireText(id, "id");
        RegisteredClient fixed = fixedById.get(id);
        if (fixed != null) return fixed;
        pruneExpired();
        DynamicClient dynamic = dynamicById.get(id);
        return dynamic == null ? null : renew(dynamic);
    }

    @Override
    public synchronized RegisteredClient findByClientId(String clientId) {
        requireText(clientId, "clientId");
        RegisteredClient fixed = fixedByClientId.get(clientId);
        if (fixed != null) return fixed;
        pruneExpired();
        DynamicClient dynamic = dynamicByClientId.get(clientId);
        return dynamic == null ? null : renew(dynamic);
    }

    private RegisteredClient renew(DynamicClient dynamic) {
        DynamicClient renewed = new DynamicClient(dynamic.client(), nextExpiry());
        List<DynamicClient> candidates = new ArrayList<>(dynamicById.values());
        candidates.removeIf(candidate -> candidate.client().getId().equals(renewed.client().getId()));
        candidates.add(renewed);
        replaceDynamicClients(candidates);
        return renewed.client();
    }

    private Instant nextExpiry() {
        return clock.instant().plus(lifetime);
    }

    private void pruneExpired() {
        List<DynamicClient> active = activeClients();
        if (active.size() != dynamicById.size()) replaceDynamicClients(active);
    }

    private List<DynamicClient> activeClients() {
        Instant now = clock.instant();
        return dynamicById.values().stream()
            .filter(dynamic -> now.isBefore(dynamic.expiresAt()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void restoreDynamicClients() {
        List<DynamicClientStateEntry> entries = stateStore.load();
        if (entries.size() > capacity) {
            throw invalidState(null);
        }
        List<DynamicClient> restored = new ArrayList<>(entries.size());
        try {
            for (DynamicClientStateEntry entry : entries) {
                if (entry == null || entry.expiresAt() == null) throw invalidState(null);
                RegisteredClient client = DynamicClientRegistrationService.restore(entry, tokenSettings);
                if (fixedById.containsKey(client.getId()) || fixedByClientId.containsKey(client.getClientId())
                    || restored.stream().anyMatch(candidate ->
                        candidate.client().getClientId().equals(client.getClientId()))) {
                    throw invalidState(null);
                }
                restored.add(new DynamicClient(client, entry.expiresAt()));
            }
        } catch (DynamicClientRegistrationException | IllegalArgumentException exception) {
            throw invalidState(exception);
        }
        List<DynamicClient> active = restored.stream()
            .filter(dynamic -> clock.instant().isBefore(dynamic.expiresAt()))
            .toList();
        if (active.size() != restored.size()) {
            replaceDynamicClients(active);
        } else {
            publishDynamicClients(active);
        }
    }

    private void replaceDynamicClients(List<DynamicClient> clients) {
        List<DynamicClient> ordered = clients.stream()
            .sorted(Comparator.comparing(dynamic -> dynamic.client().getId()))
            .toList();
        stateStore.replace(ordered.stream().map(PocketHiveRegisteredClientRepository::toState).toList());
        publishDynamicClients(ordered);
    }

    private void publishDynamicClients(List<DynamicClient> clients) {
        Map<String, DynamicClient> byId = new HashMap<>();
        Map<String, DynamicClient> byClientId = new HashMap<>();
        for (DynamicClient dynamic : clients) {
            byId.put(dynamic.client().getId(), dynamic);
            byClientId.put(dynamic.client().getClientId(), dynamic);
        }
        dynamicById = Map.copyOf(byId);
        dynamicByClientId = Map.copyOf(byClientId);
    }

    private static DynamicClientStateEntry toState(DynamicClient dynamic) {
        RegisteredClient client = dynamic.client();
        return new DynamicClientStateEntry(
            client.getId(), client.getClientId(), client.getClientIdIssuedAt(), client.getClientName(),
            List.copyOf(client.getRedirectUris()),
            client.getAuthorizationGrantTypes().stream().map(type -> type.getValue()).sorted().toList(),
            client.getScopes().stream().sorted().toList(), dynamic.expiresAt());
    }

    private static IllegalStateException invalidState(Exception cause) {
        return new IllegalStateException("OAUTH_DYNAMIC_CLIENT_STATE_INVALID", cause);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
    }

    private record DynamicClient(RegisteredClient client, Instant expiresAt) {
    }
}
