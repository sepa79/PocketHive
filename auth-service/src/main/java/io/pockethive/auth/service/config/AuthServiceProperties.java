package io.pockethive.auth.service.config;

import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.auth-service")
public class AuthServiceProperties {
    private AuthProvider provider = AuthProvider.DEV;
    private Duration sessionTtl = Duration.ofHours(8);
    private List<UserConfig> users = new ArrayList<>();

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

    public List<UserConfig> getUsers() {
        return users;
    }

    public void setUsers(List<UserConfig> users) {
        this.users = users == null ? new ArrayList<>() : new ArrayList<>(users);
    }

    public static class UserConfig {
        private UUID id;
        private String username;
        private String displayName;
        private boolean active = true;
        private List<AuthGrantDto> grants = new ArrayList<>();

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public List<AuthGrantDto> getGrants() {
            return grants;
        }

        public void setGrants(List<AuthGrantDto> grants) {
            this.grants = grants == null ? new ArrayList<>() : new ArrayList<>(grants);
        }
    }
}
