package io.pockethive.mcp.config;

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

@Validated
@ConfigurationProperties("pockethive.mcp")
public record PocketHiveMcpProperties(
    @NotNull URI pocketHiveIngress,
    @NotNull URI ownerApiBase,
    @NotNull StateMode stateMode,
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
    public enum StateMode {
        FILE,
        MEMORY
    }

    @AssertTrue(message = "production ingress and OAuth resource must use HTTPS; HTTP is loopback-only")
    public boolean hasSecureEndpoints() {
        return secureOrLoopback(pocketHiveIngress) && secureOrLoopback(oauthResource)
            && secureOrLoopback(oauthIssuer);
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

    private static boolean secureOrLoopback(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        return "http".equalsIgnoreCase(uri.getScheme())
            && ("localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost())
                || "::1".equals(uri.getHost()));
    }

    private static boolean networkUri(URI uri) {
        return uri != null && uri.getHost() != null
            && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
    }
}
