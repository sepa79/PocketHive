package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmPlan(String id, @Valid List<Bee> bees) {
    public SwarmPlan {
        bees = bees == null ? List.of() : List.copyOf(bees);
    }

    public Map<String, String> capabilitiesVersionsByRole() {
        if (bees.isEmpty()) {
            return Map.of();
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (Bee bee : bees) {
            if (bee == null) {
                continue;
            }
            String role = bee.role();
            String version = bee.capabilitiesVersion();
            if (role == null || role.isBlank() || version == null || version.isBlank()) {
                continue;
            }
            versions.put(role, version);
        }
        if (versions.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(versions);
    }
}
