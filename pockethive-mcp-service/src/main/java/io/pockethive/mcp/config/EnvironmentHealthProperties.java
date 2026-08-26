package io.pockethive.mcp.config;

import io.pockethive.mcp.application.EnvironmentHealthTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: Bind and validate the canonical environment-health probe catalogue.
 * Must not: Own domain transitions or reconstruct configuration outside the canonical properties.
 * Contract: docs/mcp/README.md.
 */

@Validated
@ConfigurationProperties("pockethive.mcp.environment-health")
public record EnvironmentHealthProperties(
    @NotNull Duration probeTimeout,
    @NotEmpty List<@Valid EnvironmentHealthTargetProperties> targets
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
        return targets.stream().map(EnvironmentHealthTargetProperties::toApplicationTarget).toList();
    }
}
