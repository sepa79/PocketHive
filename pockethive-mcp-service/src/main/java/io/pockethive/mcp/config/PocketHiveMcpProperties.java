package io.pockethive.mcp.config;

import io.pockethive.auth.contract.PublicEndpointTransportPolicy;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: Bind and validate the canonical MCP owner, security, and capacity configuration.
 * Must not: Own domain transitions or reconstruct configuration outside the canonical properties.
 * Contract: RESP-MCP-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-mcp-configuration.
 */

@Validated
@ConfigurationProperties("pockethive.mcp")
public record PocketHiveMcpProperties(
    boolean allowRemoteHttp,
    @NotNull URI pocketHiveIngress,
    @NotNull URI ownerApiBase,
    @NotNull McpStateMode stateMode,
    @NotNull Path statePath,
    @NotNull Path uploadSpoolPath,
    @NotNull Duration openSessionTtl,
    @NotNull Duration closedSessionRetention,
    @NotNull Duration attemptRetention,
    @NotNull Duration receiptRetention,
    @NotNull Duration uploadTicketTtl,
    @Min(1) int maxOpenSessions,
    @Min(1) int maxOpenSessionsPerPrincipal,
    @Min(1) int maxTransportSessions,
    @Min(1) int maxWorkflowsPerSession,
    @Min(1) long maxStateBytes,
    @Min(1) int maxConcurrentUploadsPerPrincipal,
    @Min(1) int maxConcurrentUploads,
    @Min(1) long maxUploadBytes,
    @Min(1) long maxUploadSpoolBytes,
    @Min(1) int maxArchiveFiles,
    @Min(1) long maxArchiveExpandedBytes,
    @Min(0) int maxArchiveNesting,
    @Min(1) int maxArchiveCompressionRatio,
    @NotEmpty List<String> allowedOrigins,
    @NotEmpty List<String> allowedHosts,
    @NotNull URI oauthIssuer,
    @NotNull URI oauthResource,
    @NotNull URI oauthIntrospectionUri,
    @NotBlank String oauthIntrospectionClientId,
    @NotBlank String oauthIntrospectionClientSecret,
    @NotBlank String downstreamServiceName,
    @NotBlank String downstreamServiceSecret
) {
    @AssertTrue(message = "public endpoints require HTTPS or loopback HTTP unless POCKETHIVE_ALLOW_REMOTE_HTTP=true")
    public boolean hasAllowedPublicEndpoints() {
        return PublicEndpointTransportPolicy.allows(pocketHiveIngress, allowRemoteHttp)
            && PublicEndpointTransportPolicy.allows(oauthResource, allowRemoteHttp)
            && PublicEndpointTransportPolicy.allows(oauthIssuer, allowRemoteHttp);
    }

    @AssertTrue(message = "ownerApiBase must be an HTTP(S) origin without credentials, path, query, or fragment")
    public boolean hasValidOwnerApiBase() {
        return networkUri(ownerApiBase)
            && (ownerApiBase.getPath() == null || ownerApiBase.getPath().isBlank()
                || "/".equals(ownerApiBase.getPath()));
    }

    @AssertTrue(message = "oauthIntrospectionUri must be a fixed HTTP(S) endpoint")
    public boolean hasValidIntrospectionEndpoint() {
        return networkUri(oauthIntrospectionUri) && oauthIntrospectionUri.getPath() != null
            && !oauthIntrospectionUri.getPath().isBlank();
    }

    @AssertTrue(message = "per-principal limits must not exceed instance limits")
    public boolean hasConsistentLimits() {
        return maxOpenSessionsPerPrincipal <= maxOpenSessions
            && maxConcurrentUploadsPerPrincipal <= maxConcurrentUploads
            && maxUploadBytes <= maxUploadSpoolBytes;
    }

    private static boolean networkUri(URI uri) {
        return uri != null && uri.getHost() != null
            && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
    }
}
