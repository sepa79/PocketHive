package io.pockethive.auth.service.config;

import io.pockethive.auth.contract.AuthGrantDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Responsibility: Bind one configured Auth Service user and its explicit grants.
 * Must not: Authenticate the user or decide authorization outcomes.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */
public final class AuthServiceUserProperties {
    private UUID id;
    private String username;
    private String displayName;
    private boolean active = true;
    private List<AuthGrantDto> grants = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<AuthGrantDto> getGrants() { return grants; }
    public void setGrants(List<AuthGrantDto> grants) {
        this.grants = grants == null ? new ArrayList<>() : new ArrayList<>(grants);
    }
}
