package io.pockethive.swarm.model;

import jakarta.validation.constraints.NotBlank;

public record TopologyEndpoint(@NotBlank String role, @NotBlank String port) {
}
