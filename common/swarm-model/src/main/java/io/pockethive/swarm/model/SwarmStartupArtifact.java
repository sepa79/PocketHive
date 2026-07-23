package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable input used by a swarm controller to prepare one swarm runtime. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SwarmStartupArtifact(@NotBlank String schema,
                                   @NotNull @Valid SwarmPlan swarmPlan,
                                   @NotNull Map<String, Object> scenarioPlan) {

    public SwarmStartupArtifact {
        if (!SwarmStartupArtifactContract.SCHEMA_V1.equals(schema)) {
            throw new IllegalArgumentException("Unsupported swarm startup artifact schema: " + schema);
        }
        if (swarmPlan == null) {
            throw new IllegalArgumentException("swarmPlan must not be null");
        }
        scenarioPlan = scenarioPlan == null
            ? null
            : Map.copyOf(new LinkedHashMap<>(scenarioPlan));
        if (scenarioPlan == null) {
            throw new IllegalArgumentException("scenarioPlan must not be null");
        }
    }

    public static SwarmStartupArtifact v1(SwarmPlan swarmPlan, Map<String, Object> scenarioPlan) {
        return new SwarmStartupArtifact(SwarmStartupArtifactContract.SCHEMA_V1, swarmPlan, scenarioPlan);
    }
}
