package io.pockethive.mcp.config;

import io.pockethive.mcp.application.EnvironmentHealthContract;
import io.pockethive.mcp.application.EnvironmentHealthTarget;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;

/**
 * Responsibility: Bind and validate one explicitly configured public-ingress health target.
 * Must not: Probe services or decide their runtime health.
 * Contract: pockethive.mcp.environment-health.targets in docs/mcp/README.md.
 */
public record EnvironmentHealthTargetProperties(
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

    EnvironmentHealthTarget toApplicationTarget() {
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
