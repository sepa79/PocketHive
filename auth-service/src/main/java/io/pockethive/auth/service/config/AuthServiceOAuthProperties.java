package io.pockethive.auth.service.config;

import java.net.URI;
import java.time.Duration;

/**
 * Responsibility: Bind the canonical OAuth issuer, client, capacity, and token-lifetime configuration.
 * Must not: Register clients, issue tokens, or infer missing deployment endpoints.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */
public final class AuthServiceOAuthProperties {
    private URI issuer;
    private URI resource;
    private Duration authorizationCodeTtl = Duration.ofMinutes(2);
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private Duration dynamicClientTtl = Duration.ofDays(31);
    private int dynamicClientCapacity = 256;
    private String vscodeClientId;
    private URI vscodeRedirectUri;
    private String introspectionClientId;
    private String introspectionClientSecret;

    public URI getIssuer() { return issuer; }
    public void setIssuer(URI issuer) { this.issuer = issuer; }
    public URI getResource() { return resource; }
    public void setResource(URI resource) { this.resource = resource; }
    public Duration getAuthorizationCodeTtl() { return authorizationCodeTtl; }
    public void setAuthorizationCodeTtl(Duration value) { this.authorizationCodeTtl = value; }
    public Duration getAccessTokenTtl() { return accessTokenTtl; }
    public void setAccessTokenTtl(Duration value) { this.accessTokenTtl = value; }
    public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
    public void setRefreshTokenTtl(Duration value) { this.refreshTokenTtl = value; }
    public Duration getDynamicClientTtl() { return dynamicClientTtl; }
    public void setDynamicClientTtl(Duration value) { this.dynamicClientTtl = value; }
    public int getDynamicClientCapacity() { return dynamicClientCapacity; }
    public void setDynamicClientCapacity(int value) { this.dynamicClientCapacity = value; }
    public String getVscodeClientId() { return vscodeClientId; }
    public void setVscodeClientId(String value) { this.vscodeClientId = value; }
    public URI getVscodeRedirectUri() { return vscodeRedirectUri; }
    public void setVscodeRedirectUri(URI value) { this.vscodeRedirectUri = value; }
    public String getIntrospectionClientId() { return introspectionClientId; }
    public void setIntrospectionClientId(String value) { this.introspectionClientId = value; }
    public String getIntrospectionClientSecret() { return introspectionClientSecret; }
    public void setIntrospectionClientSecret(String value) { this.introspectionClientSecret = value; }
}
