package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Bee(String id,
                  @NotBlank String role,
                  @NotBlank String image,
                  @Valid @NotNull Work work,
                  @Valid List<BeePort> ports,
                  Map<String, String> env,
                  Map<String, Object> config) {
    public Bee {
        id = normalizeId(id);
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
        this(null, role, image, work, List.of(), env, config);
    }

    public Bee(String role,
               String image,
               Work work,
               Map<String, String> env) {
        this(null, role, image, work, List.of(), env, null);
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
