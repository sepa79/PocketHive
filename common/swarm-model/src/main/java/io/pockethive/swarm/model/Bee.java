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
                  String capabilitiesVersion,
                  Map<String, Object> manifestHints) {
    public Bee {
        work = Objects.requireNonNull(work, "work");
        env = env == null || env.isEmpty() ? Map.of() : Map.copyOf(env);
        manifestHints = manifestHints == null || manifestHints.isEmpty()
            ? Map.of()
            : Map.copyOf(manifestHints);
        if (capabilitiesVersion != null && capabilitiesVersion.isBlank()) {
            capabilitiesVersion = null;
        }
    }

    public Bee(String role, String image, Work work, Map<String, String> env) {
        this(role, image, work, env, null, Map.of());
    }

    public Bee(String role,
               String image,
               Work work,
               Map<String, String> env,
               String capabilitiesVersion) {
        this(role, image, work, env, capabilitiesVersion, Map.of());
    }

    public Bee withManifestHints(Map<String, Object> hints) {
        Map<String, Object> safeHints = hints == null || hints.isEmpty()
            ? Map.of()
            : Map.copyOf(hints);
        return new Bee(role, image, work, env, capabilitiesVersion, safeHints);
    }
}
