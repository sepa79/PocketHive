package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates runtime capabilities manifests published by active swarms.
 */
public class RuntimeCapabilitiesCatalogue {
    private final Map<String, Map<String, Map<String, CapabilityRecord>>> bySwarm = new ConcurrentHashMap<>();

    public void update(String swarmId, JsonNode runtimeCapabilities) {
        if (swarmId == null || swarmId.isBlank()) {
            return;
        }
        if (runtimeCapabilities == null || runtimeCapabilities.isMissingNode() || runtimeCapabilities.isNull()) {
            return;
        }
        Map<String, Map<String, CapabilityRecord>> snapshot = new LinkedHashMap<>();
        runtimeCapabilities.fields().forEachRemaining(roleEntry -> {
            String role = roleEntry.getKey();
            JsonNode versionsNode = roleEntry.getValue();
            if (role == null || role.isBlank() || versionsNode == null || !versionsNode.isObject()) {
                return;
            }
            Map<String, CapabilityRecord> byVersion = new LinkedHashMap<>();
            versionsNode.fields().forEachRemaining(versionEntry -> {
                String version = versionEntry.getKey();
                JsonNode payload = versionEntry.getValue();
                if (version == null || version.isBlank() || payload == null || !payload.isObject()) {
                    return;
                }
                JsonNode manifestNode = payload.path("manifest");
                if (manifestNode == null || manifestNode.isMissingNode() || manifestNode.isNull() || !manifestNode.isObject()) {
                    return;
                }
                ArrayNode instancesNode = payload.path("instances").isArray()
                    ? (ArrayNode) payload.path("instances")
                    : null;
                LinkedHashSet<String> instances = new LinkedHashSet<>();
                if (instancesNode != null) {
                    instancesNode.forEach(n -> {
                        String value = n.asText(null);
                        if (value != null && !value.isBlank()) {
                            instances.add(value);
                        }
                    });
                }
                byVersion.put(version, new CapabilityRecord((ObjectNode) manifestNode.deepCopy(), instances));
            });
            if (!byVersion.isEmpty()) {
                snapshot.put(role, byVersion);
            }
        });
        if (!snapshot.isEmpty()) {
            bySwarm.put(swarmId, snapshot);
        }
    }

    public boolean hasVersion(String role, String version) {
        if (role == null || role.isBlank() || version == null || version.isBlank()) {
            return false;
        }
        return bySwarm.values().stream()
            .map(roleMap -> roleMap.get(role))
            .filter(Objects::nonNull)
            .anyMatch(byVersion -> byVersion.containsKey(version));
    }

    public Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        bySwarm.forEach((swarmId, roles) -> {
            Map<String, Object> roleView = new LinkedHashMap<>();
            roles.forEach((role, versions) -> {
                Map<String, Object> versionView = new LinkedHashMap<>();
                versions.forEach((version, record) -> versionView.put(version, record.toView()));
                roleView.put(role, Collections.unmodifiableMap(versionView));
            });
            view.put(swarmId, Collections.unmodifiableMap(roleView));
        });
        return Collections.unmodifiableMap(view);
    }

    public Set<String> knownVersions(String role) {
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        bySwarm.values().stream()
            .map(roleMap -> roleMap.get(role))
            .filter(Objects::nonNull)
            .forEach(map -> versions.addAll(map.keySet()));
        return Collections.unmodifiableSet(versions);
    }

    public Optional<Map<String, Map<String, CapabilityRecord>>> findBySwarm(String swarmId) {
        if (swarmId == null || swarmId.isBlank()) {
            return Optional.empty();
        }
        Map<String, Map<String, CapabilityRecord>> snapshot = bySwarm.get(swarmId);
        if (snapshot == null) {
            return Optional.empty();
        }
        Map<String, Map<String, CapabilityRecord>> copy = new LinkedHashMap<>();
        snapshot.forEach((role, versions) -> {
            Map<String, CapabilityRecord> versionCopy = new LinkedHashMap<>();
            versions.forEach((version, record) -> versionCopy.put(version, record.copy()));
            copy.put(role, versionCopy);
        });
        return Optional.of(copy);
    }

    public record CapabilityRecord(ObjectNode manifest, Set<String> instances, Instant updatedAt) {
        CapabilityRecord(ObjectNode manifest, Set<String> instances) {
            this(manifest, instances, Instant.now());
        }

        public CapabilityRecord copy() {
            return new CapabilityRecord((ObjectNode) manifest.deepCopy(), new LinkedHashSet<>(instances), updatedAt);
        }

        Map<String, Object> toView() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("manifest", manifest.deepCopy());
            payload.put("instances", List.copyOf(instances));
            payload.put("updatedAt", updatedAt);
            return Collections.unmodifiableMap(payload);
        }
    }
}
