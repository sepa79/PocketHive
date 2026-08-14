package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
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
        if (scenarioPlan == null) {
            throw new IllegalArgumentException("scenarioPlan must not be null");
        }
        // scenarioPlan is arbitrary JSON. JSON null values are valid, while Map.copyOf rejects
        // them. Keep the boundary value immutable without narrowing the JSON contract.
        scenarioPlan = Collections.unmodifiableMap(new LinkedHashMap<>(scenarioPlan));
    }

    public static SwarmStartupArtifact v1(SwarmPlan swarmPlan, Map<String, Object> scenarioPlan) {
        return new SwarmStartupArtifact(SwarmStartupArtifactContract.SCHEMA_V1, swarmPlan, scenarioPlan);
    }
}
