package io.pockethive.swarm.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Bee(@NotBlank String role,
                  @NotBlank String image,
                  @Valid @NotNull Work work,
                  @Valid List<BeePort> ports,
                  Map<String, String> env,
                  Map<String, Object> config) {
    public Bee {
        work = Objects.requireNonNull(work, "work");
        ports = ports == null ? List.of() : List.copyOf(ports);
        env = env == null || env.isEmpty() ? Map.of() : Map.copyOf(env);
        config = config == null || config.isEmpty() ? Map.of() : Map.copyOf(config);
    }

    public Bee(String role,
               String image,
               Work work,
               Map<String, String> env,
               Map<String, Object> config) {
        this(role, image, work, List.of(), env, config);
    }

    public Bee(String role,
               String image,
               Work work,
               Map<String, String> env) {
        this(role, image, work, List.of(), env, null);
    }
}
