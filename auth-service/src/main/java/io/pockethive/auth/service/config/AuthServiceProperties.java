package io.pockethive.auth.service.config;

import io.pockethive.auth.contract.AuthProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Responsibility: Bind the root Auth Service configuration and its focused child property types.
 * Must not: Authenticate principals, issue tokens, or own child configuration behavior.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */
@ConfigurationProperties(prefix = "pockethive.auth-service")
public class AuthServiceProperties {
    private AuthProvider provider = AuthProvider.DEV;
    private Duration sessionTtl = Duration.ofHours(8);
    private List<AuthServiceUserProperties> users = new ArrayList<>();
    private List<AuthServiceAccountProperties> serviceAccounts = new ArrayList<>();
    private AuthServiceOAuthProperties oauth = new AuthServiceOAuthProperties();

    public AuthProvider getProvider() {
        return provider;
    }

    public void setProvider(AuthProvider provider) {
        this.provider = provider;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public List<AuthServiceUserProperties> getUsers() {
        return users;
    }

    public void setUsers(List<AuthServiceUserProperties> users) {
        this.users = users == null ? new ArrayList<>() : new ArrayList<>(users);
    }

    public List<AuthServiceAccountProperties> getServiceAccounts() {
        return serviceAccounts;
    }

    public void setServiceAccounts(List<AuthServiceAccountProperties> serviceAccounts) {
        this.serviceAccounts = serviceAccounts == null ? new ArrayList<>() : new ArrayList<>(serviceAccounts);
    }

    public AuthServiceOAuthProperties getOauth() {
        return oauth;
    }

    public void setOauth(AuthServiceOAuthProperties oauth) {
        this.oauth = oauth;
    }
}
