package io.pockethive.auth.service.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** One bounded owner for immutable first-party and expiring dynamically registered clients. */
public final class PocketHiveRegisteredClientRepository implements RegisteredClientRepository {
    private final Map<String, RegisteredClient> fixedById;
    private final Map<String, RegisteredClient> fixedByClientId;
    private final Map<String, DynamicClient> dynamicById = new HashMap<>();
    private final Map<String, DynamicClient> dynamicByClientId = new HashMap<>();
    private final int capacity;
    private final Duration lifetime;
    private final Clock clock;

    public PocketHiveRegisteredClientRepository(List<RegisteredClient> fixedClients, int capacity,
                                                Duration lifetime, Clock clock) {
        if (fixedClients == null || fixedClients.isEmpty() || capacity < 1 || lifetime == null
            || lifetime.isZero() || lifetime.isNegative() || clock == null) {
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
    }

    @Override
    public synchronized void save(RegisteredClient client) {
        Objects.requireNonNull(client, "registeredClient");
        pruneExpired();
        if (fixedById.containsKey(client.getId()) || fixedByClientId.containsKey(client.getClientId())) {
            throw new IllegalArgumentException("OAUTH_FIXED_CLIENT_IMMUTABLE");
        }
        if (dynamicById.containsKey(client.getId())
            || dynamicByClientId.containsKey(client.getClientId())) {
            throw new IllegalArgumentException("OAUTH_DYNAMIC_CLIENT_IDENTIFIER_DUPLICATE");
        }
        if (dynamicById.size() >= capacity) {
            throw new DynamicClientCapacityException();
        }
        DynamicClient saved = new DynamicClient(client, nextExpiry());
        dynamicById.put(client.getId(), saved);
        dynamicByClientId.put(client.getClientId(), saved);
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
        dynamicById.put(renewed.client().getId(), renewed);
        dynamicByClientId.put(renewed.client().getClientId(), renewed);
        return renewed.client();
    }

    private Instant nextExpiry() {
        return clock.instant().plus(lifetime);
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        dynamicById.values().removeIf(dynamic -> {
            if (now.isBefore(dynamic.expiresAt())) return false;
            dynamicByClientId.remove(dynamic.client().getClientId());
            return true;
        });
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
    }

    private record DynamicClient(RegisteredClient client, Instant expiresAt) {
    }
}
