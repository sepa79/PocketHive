package io.pockethive.mcp.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: Bind the canonical MCP owner-auth HTTP deadlines.
 * Must not: Create clients, select identity or infer an ingress.
 * Contract: RESP-POCKET-AUTH-HTTP — docs/architecture/runtime-responsibilities.md#resp-pocket-auth-http.
 */
@Validated
@ConfigurationProperties("pockethive.mcp.owner-auth")
public record McpOwnerAuthProperties(
    @NotNull @DefaultValue("5s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout
) {
    @AssertTrue(message = "owner-auth deadlines must be positive")
    public boolean hasPositiveTimeouts() {
        return connectTimeout != null && readTimeout != null
            && connectTimeout.toMillis() > 0 && readTimeout.toMillis() > 0;
    }
}
