package io.pockethive.mcp.config;

import io.pockethive.mcp.application.EnvironmentHealthContract;
import io.pockethive.mcp.application.EnvironmentHealthTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("pockethive.mcp.environment-health")
public record EnvironmentHealthProperties(
    @NotNull Duration probeTimeout,
    @NotEmpty List<@Valid Target> targets
) {
    public EnvironmentHealthProperties {
        targets = targets == null ? null : List.copyOf(targets);
    }

    @AssertTrue(message = "probeTimeout must be positive")
    public boolean hasPositiveProbeTimeout() {
        return probeTimeout != null && !probeTimeout.isZero() && !probeTimeout.isNegative();
    }

    @AssertTrue(message = "environment health target ids must be unique")
    public boolean hasUniqueTargetIds() {
        if (targets == null) {
            return false;
        }
        HashSet<String> ids = new HashSet<>();
        return targets.stream().allMatch(target -> target != null && ids.add(target.id()));
    }

    public List<EnvironmentHealthTarget> catalogue() {
        return targets.stream().map(Target::toApplicationTarget).toList();
    }

    public record Target(
        @NotBlank String id,
        @NotBlank String name,
        @NotBlank String endpointPath,
        @NotBlank String probePath,
        @NotNull EnvironmentHealthContract contract
    ) {
        @AssertTrue(message = "environment health paths must be absolute paths without authority, query, or fragment")
        public boolean hasValidPaths() {
            return validPath(endpointPath) && validPath(probePath);
        }

        private EnvironmentHealthTarget toApplicationTarget() {
            return new EnvironmentHealthTarget(id, name, URI.create(endpointPath), probePath, contract);
        }

        private static boolean validPath(String value) {
            if (value == null) {
                return false;
            }
            try {
                URI uri = URI.create(value);
                return value.startsWith("/")
                    && !uri.isAbsolute()
                    && uri.getRawAuthority() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }
}
