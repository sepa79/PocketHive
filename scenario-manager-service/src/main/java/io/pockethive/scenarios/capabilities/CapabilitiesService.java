package io.pockethive.scenarios.capabilities;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.pockethive.scenarios.OrchestratorCapabilitiesClient;
import io.pockethive.scenarios.OrchestratorCapabilitiesClient.OrchestratorRuntimeResponse;
import io.pockethive.scenarios.ScenarioManagerProperties;

@Service
public class CapabilitiesService {
    private static final Logger log = LoggerFactory.getLogger(CapabilitiesService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final OrchestratorCapabilitiesClient orchestratorClient;
    private final ScenarioManagerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OfflinePack offlinePack;
    private final AtomicReference<CatalogueCache> cache = new AtomicReference<>(CatalogueCache.empty());

    public CapabilitiesService(
            OrchestratorCapabilitiesClient orchestratorClient,
            ScenarioManagerProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.orchestratorClient = orchestratorClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.offlinePack = loadOfflinePack(properties.capabilities().getOfflinePackPath());
    }

    public Map<String, Object> runtimeCatalogue() {
        Instant now = clock.instant();
        refreshIfNecessary(now);
        CatalogueCache snapshot = cache.get();
        Map<String, Object> merged = new LinkedHashMap<>(snapshot.runtimeCatalogue());
        if (!offlinePack.catalogue().isEmpty()) {
            String offlineSwarmId = Optional.ofNullable(properties.capabilities().getOfflineSwarmId())
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .orElse("offline-pack");
            merged.put(offlineSwarmId, offlinePack.catalogue());
        }
        return Collections.unmodifiableMap(merged);
    }

    public CapabilitiesStatus status() {
        CatalogueCache snapshot = cache.get();
        Instant now = clock.instant();
        Duration ttl = properties.capabilities().getCacheTtl();
        boolean stale = snapshot.isExpired(now, ttl);
        return new CapabilitiesStatus(
                snapshot.lastFetchAttempt(),
                snapshot.lastSuccessfulFetch(),
                ttl,
                stale,
                snapshot.runtimeCatalogue().size(),
                snapshot.lastFailureMessage(),
                offlinePack.status());
    }

    private void refreshIfNecessary(Instant now) {
        cache.updateAndGet(current -> {
            if (!current.isExpired(now, properties.capabilities().getCacheTtl())) {
                return current;
            }
            Instant attempt = now;
            try {
                Map<String, Object> runtime = normalizeRuntime(fetchRuntimeCatalogue());
                return current.success(runtime, attempt);
            } catch (Exception ex) {
                log.warn("Failed to fetch runtime capabilities from orchestrator", ex);
                return current.failure(attempt, ex.getMessage());
            }
        });
    }

    private Map<String, Object> fetchRuntimeCatalogue() {
        OrchestratorRuntimeResponse response = orchestratorClient.fetchRuntimeCatalogue();
        if (response == null || response.catalogue() == null) {
            return Map.of();
        }
        return response.catalogue();
    }

    private Map<String, Object> normalizeRuntime(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((swarmKey, rolesRaw) -> {
            if (!(swarmKey instanceof String swarmId)) {
                return;
            }
            swarmId = swarmId.trim();
            if (swarmId.isEmpty()) {
                return;
            }
            Map<String, Object> roles = normalizeRoles(rolesRaw);
            if (!roles.isEmpty()) {
                result.put(swarmId, roles);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> normalizeRoles(Object raw) {
        Map<String, Object> map = toMap(raw);
        if (map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((roleKey, versionsRaw) -> {
            if (!(roleKey instanceof String role)) {
                return;
            }
            role = role.trim();
            if (role.isEmpty()) {
                return;
            }
            Map<String, Object> versions = normalizeVersions(versionsRaw);
            if (!versions.isEmpty()) {
                result.put(role, versions);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> normalizeVersions(Object raw) {
        Map<String, Object> map = toMap(raw);
        if (map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((versionKey, entryRaw) -> {
            if (!(versionKey instanceof String version)) {
                return;
            }
            version = version.trim();
            if (version.isEmpty()) {
                return;
            }
            Map<String, Object> entry = normalizeEntry(entryRaw);
            if (!entry.isEmpty()) {
                result.put(version, entry);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> normalizeEntry(Object raw) {
        Map<String, Object> map = toMap(raw);
        if (map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> manifest = normalizeManifest(map.get("manifest"));
        if (manifest.isEmpty()) {
            return Map.of();
        }
        List<String> instances = normalizeInstances(map.get("instances"));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("manifest", manifest);
        entry.put("instances", instances);
        Object updatedAt = map.get("updatedAt");
        if (updatedAt instanceof String str && !str.isBlank()) {
            entry.put("updatedAt", str);
        } else if (updatedAt instanceof Instant instant) {
            entry.put("updatedAt", instant.toString());
        }
        return Collections.unmodifiableMap(entry);
    }

    private Map<String, Object> normalizeManifest(Object raw) {
        Map<String, Object> map = toMap(raw);
        if (map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                manifest.put(stringKey, value);
            }
        });
        return Collections.unmodifiableMap(manifest);
    }

    private List<String> normalizeInstances(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> instances = new ArrayList<>();
        if (raw instanceof JsonNode node && node.isArray()) {
            node.forEach(child -> {
                if (child.isTextual()) {
                    String value = child.asText().trim();
                    if (!value.isEmpty()) {
                        instances.add(value);
                    }
                }
            });
        } else if (raw instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (element instanceof String str) {
                    str = str.trim();
                    if (!str.isEmpty()) {
                        instances.add(str);
                    }
                }
            }
        } else if (raw instanceof String str) {
            str = str.trim();
            if (!str.isEmpty()) {
                instances.add(str);
            }
        }
        return List.copyOf(instances);
    }

    private Map<String, Object> toMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof JsonNode node) {
            if (!node.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(node, MAP_TYPE);
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(Objects.toString(key, null), value));
            return result;
        }
        return Map.of();
    }

    private OfflinePack loadOfflinePack(Path path) {
        if (path == null) {
            return OfflinePack.missing();
        }
        Path normalized = path.toAbsolutePath();
        if (!Files.exists(normalized)) {
            log.info("Offline capabilities pack not found at {}", normalized);
            return OfflinePack.missing();
        }
        try {
            JsonNode root = objectMapper.readTree(normalized.toFile());
            JsonNode catalogueNode = root.path("catalogue");
            Map<String, Object> roles = catalogueNode.isMissingNode()
                    ? normalizeRoles(root)
                    : normalizeRoles(catalogueNode);
            Map<String, Object> metadata = extractOfflineMetadata(root);
            Instant lastModified = Files.getLastModifiedTime(normalized).toInstant();
            OfflineStatus status = new OfflineStatus(true, normalized.toString(), lastModified, roles.size(), metadata);
            return new OfflinePack(roles, status);
        } catch (IOException e) {
            log.warn("Failed to load offline capabilities pack from {}", normalized, e);
            return OfflinePack.missing();
        }
    }

    private Map<String, Object> extractOfflineMetadata(JsonNode root) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (root.has("metadata") && root.get("metadata").isObject()) {
            metadata.putAll(objectMapper.convertValue(root.get("metadata"), MAP_TYPE));
        }
        if (root.has("generatedAt") && root.get("generatedAt").isTextual()) {
            metadata.put("generatedAt", root.get("generatedAt").asText());
        }
        if (root.has("version") && root.get("version").isTextual()) {
            metadata.put("version", root.get("version").asText());
        }
        if (root.has("checksum") && root.get("checksum").isTextual()) {
            metadata.put("checksum", root.get("checksum").asText());
        }
        return metadata;
    }

    private record OfflinePack(Map<String, Object> catalogue, OfflineStatus status) {
        static OfflinePack missing() {
            return new OfflinePack(Map.of(), OfflineStatus.missing());
        }
    }

    private static final class CatalogueCache {
        private final Map<String, Object> runtimeCatalogue;
        private final Instant lastFetchAttempt;
        private final Instant lastSuccessfulFetch;
        private final String lastFailureMessage;

        private CatalogueCache(
                Map<String, Object> runtimeCatalogue,
                Instant lastFetchAttempt,
                Instant lastSuccessfulFetch,
                String lastFailureMessage) {
            this.runtimeCatalogue = runtimeCatalogue;
            this.lastFetchAttempt = lastFetchAttempt;
            this.lastSuccessfulFetch = lastSuccessfulFetch;
            this.lastFailureMessage = lastFailureMessage;
        }

        static CatalogueCache empty() {
            return new CatalogueCache(Collections.unmodifiableMap(new LinkedHashMap<>()), null, null, null);
        }

        CatalogueCache success(Map<String, Object> runtimeCatalogue, Instant attempt) {
            return new CatalogueCache(Collections.unmodifiableMap(new LinkedHashMap<>(runtimeCatalogue)), attempt, attempt, null);
        }

        CatalogueCache failure(Instant attempt, String failureMessage) {
            return new CatalogueCache(runtimeCatalogue, attempt, lastSuccessfulFetch, failureMessage);
        }

        boolean isExpired(Instant now, Duration ttl) {
            if (lastSuccessfulFetch == null) {
                return true;
            }
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                return true;
            }
            Instant expiry = lastSuccessfulFetch.plus(ttl);
            return !now.isBefore(expiry);
        }

        Map<String, Object> runtimeCatalogue() {
            return runtimeCatalogue;
        }

        Instant lastFetchAttempt() {
            return lastFetchAttempt;
        }

        Instant lastSuccessfulFetch() {
            return lastSuccessfulFetch;
        }

        String lastFailureMessage() {
            return lastFailureMessage;
        }
    }
}
