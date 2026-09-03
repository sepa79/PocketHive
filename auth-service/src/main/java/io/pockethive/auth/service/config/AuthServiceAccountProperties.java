package io.pockethive.auth.service.config;

import io.pockethive.auth.contract.AuthGrantDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Responsibility: Bind one configured Auth Service service account and its explicit grants.
 * Must not: Authenticate the service account or decide authorization outcomes.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */
public final class AuthServiceAccountProperties {
    private UUID id;
    private String serviceName;
    private String displayName;
    private String secret;
    private boolean active = true;
    private List<AuthGrantDto> grants = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<AuthGrantDto> getGrants() { return grants; }
    public void setGrants(List<AuthGrantDto> grants) {
        this.grants = grants == null ? new ArrayList<>() : new ArrayList<>(grants);
    }
}
