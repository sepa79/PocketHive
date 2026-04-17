package io.pockethive.auth.service.service;

import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.DevLoginRequestDto;
import io.pockethive.auth.contract.SessionResponseDto;
import io.pockethive.auth.service.domain.StoredUser;
import io.pockethive.auth.service.support.AuthGrantChecks;
import io.pockethive.auth.service.support.BearerTokenSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthAccessService {
    private final AuthSessionService sessions;
    private final InMemoryUserStore users;
    private final BearerTokenSupport bearerTokens;
    private final AuthGrantChecks grants;

    public AuthAccessService(AuthSessionService sessions,
                             InMemoryUserStore users,
                             BearerTokenSupport bearerTokens,
                             AuthGrantChecks grants) {
        this.sessions = sessions;
        this.users = users;
        this.bearerTokens = bearerTokens;
        this.grants = grants;
    }

    public SessionResponseDto devLogin(DevLoginRequestDto request) {
        if (sessions.provider() != AuthProvider.DEV) {
            throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "DEV login is disabled for current provider");
        }
        StoredUser user = users.findByUsername(request.username())
            .filter(StoredUser::active)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown or inactive user"));
        return sessions.createSession(user);
    }

    public AuthenticatedUserDto requireAuthenticated(String authorizationHeader) {
        return sessions.resolve(bearerTokens.requireBearerToken(authorizationHeader));
    }

    public AuthenticatedUserDto requireAdmin(String authorizationHeader) {
        AuthenticatedUserDto user = requireAuthenticated(authorizationHeader);
        if (!grants.isAuthAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Auth admin permission required");
        }
        return user;
    }
}
