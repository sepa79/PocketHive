package io.pockethive.auth.service.service;

import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.SessionResponseDto;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.domain.AuthSession;
import io.pockethive.auth.service.domain.AuthSessionPrincipalKind;
import io.pockethive.auth.service.domain.StoredServiceAccount;
import io.pockethive.auth.service.domain.StoredUser;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthSessionService {
    private final AuthServiceProperties properties;
    private final InMemoryUserStore users;
    private final InMemoryServiceAccountStore serviceAccounts;
    private final Map<String, AuthSession> sessions = new ConcurrentHashMap<>();

    public AuthSessionService(AuthServiceProperties properties,
                              InMemoryUserStore users,
                              InMemoryServiceAccountStore serviceAccounts) {
        this.properties = properties;
        this.users = users;
        this.serviceAccounts = serviceAccounts;
    }

    public SessionResponseDto createSession(StoredUser user) {
        Instant expiresAt = Instant.now().plus(properties.getSessionTtl());
        String token = "phauth_" + UUID.randomUUID();
        sessions.put(token, new AuthSession(token, AuthSessionPrincipalKind.USER, user.id(), expiresAt));
        return new SessionResponseDto(token, "Bearer", expiresAt, user.toDto(properties.getProvider()));
    }

    public SessionResponseDto createSession(StoredServiceAccount serviceAccount) {
        Instant expiresAt = Instant.now().plus(properties.getSessionTtl());
        String token = "phauth_" + UUID.randomUUID();
        sessions.put(token, new AuthSession(token, AuthSessionPrincipalKind.SERVICE, serviceAccount.id(), expiresAt));
        return new SessionResponseDto(token, "Bearer", expiresAt, serviceAccount.toDto(properties.getProvider()));
    }

    public AuthenticatedUserDto resolve(String token) {
        AuthSession session = sessions.get(token);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown bearer token");
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expired bearer token");
        }
        if (session.principalKind() == AuthSessionPrincipalKind.USER) {
            StoredUser user = users.requireById(session.principalId());
            if (!user.active()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inactive user");
            }
            return user.toDto(properties.getProvider());
        }
        StoredServiceAccount serviceAccount = serviceAccounts.requireById(session.principalId());
        if (!serviceAccount.active()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inactive service principal");
        }
        return serviceAccount.toDto(properties.getProvider());
    }

    public AuthProvider provider() {
        return properties.getProvider();
    }
}
