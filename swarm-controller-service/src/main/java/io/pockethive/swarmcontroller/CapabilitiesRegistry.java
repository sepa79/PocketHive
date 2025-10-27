package io.pockethive.swarmcontroller;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory catalogue of runtime capabilities manifests observed from worker status events.
 */
@Component
public class CapabilitiesRegistry {
    private final Map<String, Map<String, ManifestRecord>> manifestsByRole = new ConcurrentHashMap<>();

    public void record(String role, String instance, Map<String, Object> manifest) {
        if (role == null || role.isBlank() || instance == null || instance.isBlank()) {
            return;
        }
        if (manifest == null || manifest.isEmpty()) {
            return;
        }
        Object versionValue = manifest.get("capabilitiesVersion");
        if (!(versionValue instanceof String version) || version.isBlank()) {
            return;
        }
        Map<String, ManifestRecord> byInstance = manifestsByRole.computeIfAbsent(role, r -> new ConcurrentHashMap<>());
        byInstance.put(instance, new ManifestRecord(version, Map.copyOf(manifest), Instant.now()));
    }

    public Map<String, Map<String, Map<String, Object>>> runtimeCapabilitiesView() {
        Map<String, Map<String, Map<String, Object>>> view = new TreeMap<>();
        manifestsByRole.forEach((role, byInstance) -> {
            Map<String, RuntimeAggregate> byVersion = new TreeMap<>();
            byInstance.forEach((instance, record) -> {
                if (record == null) {
                    return;
                }
                RuntimeAggregate aggregate = byVersion.computeIfAbsent(record.version(),
                    v -> new RuntimeAggregate(record.manifest(), new TreeSet<>()));
                aggregate.instances().add(instance);
            });
            if (!byVersion.isEmpty()) {
                byVersion.forEach((version, aggregate) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("manifest", aggregate.manifest());
                    payload.put("instances", List.copyOf(aggregate.instances()));
                    view.computeIfAbsent(role, r -> new LinkedHashMap<>()).put(version, Collections.unmodifiableMap(payload));
                });
            }
        });
        return Collections.unmodifiableMap(view);
    }

    public Set<String> versionsForRole(String role) {
        Map<String, ManifestRecord> byInstance = manifestsByRole.get(role);
        if (byInstance == null || byInstance.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> versions = new TreeSet<>();
        byInstance.values().stream()
            .filter(Objects::nonNull)
            .map(ManifestRecord::version)
            .filter(Objects::nonNull)
            .filter(v -> !v.isBlank())
            .forEach(versions::add);
        return Collections.unmodifiableSet(versions);
    }

    public void clear() {
        manifestsByRole.clear();
    }

    private record ManifestRecord(String version, Map<String, Object> manifest, Instant updatedAt) {
        ManifestRecord {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    private record RuntimeAggregate(Map<String, Object> manifest, Set<String> instances) {
        RuntimeAggregate {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(instances, "instances");
        }
    }
}
