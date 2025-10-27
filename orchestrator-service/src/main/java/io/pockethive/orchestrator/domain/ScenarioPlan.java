package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scenario description that can be expanded into a SwarmPlan.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioPlan(SwarmTemplate template) {
    public SwarmPlan toSwarmPlan(String id) {
        return new SwarmPlan(id, template.bees());
    }

    public Map<String, String> capabilitiesVersionsByRole() {
        if (template == null || template.bees() == null || template.bees().isEmpty()) {
            return Map.of();
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (Bee bee : template.bees()) {
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

