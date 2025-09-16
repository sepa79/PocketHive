package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Bee(@NotBlank String role,
                  @NotBlank String image,
                  @Valid Work work,
                  Map<String, String> env) {
    public Bee {
        env = env == null || env.isEmpty() ? Map.of() : Map.copyOf(env);
    }
}
