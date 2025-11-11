package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Bee(@NotBlank String role,
                  @NotBlank String image,
                  @Valid @NotNull Work work,
                  Map<String, String> env,
                  Map<String, Object> config) {
    public Bee {
        work = Objects.requireNonNull(work, "work");
        env = env == null || env.isEmpty() ? Map.of() : Map.copyOf(env);
        config = config == null || config.isEmpty() ? Map.of() : Map.copyOf(config);
    }

    public Bee(String role,
               String image,
               Work work,
               Map<String, String> env) {
        this(role, image, work, env, null);
    }
}
