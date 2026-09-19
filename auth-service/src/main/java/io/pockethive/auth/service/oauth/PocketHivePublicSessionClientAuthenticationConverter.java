package io.pockethive.auth.service.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Responsibility: Convert public-client refresh authentication requests at the token endpoint.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

/** Authenticates the pre-registered public client for refresh and revocation bearer requests. */
final class PocketHivePublicSessionClientAuthenticationConverter implements AuthenticationConverter {
    static final String SESSION_CLIENT_MARKER = PocketHivePublicSessionClientAuthenticationConverter.class.getName();
    private final String tokenPath;
    private final String revocationPath;

    PocketHivePublicSessionClientAuthenticationConverter(AuthorizationServerSettings settings) {
        this.tokenPath = settings.getTokenEndpoint();
        this.revocationPath = settings.getTokenRevocationEndpoint();
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getHeader("Authorization") != null) {
            return null;
        }
        String requestPath = applicationPath(request);
        if (requestPath == null) {
            return null;
        }
        boolean refresh = tokenPath.equals(requestPath)
            && AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
        if (!refresh && !revocationPath.equals(requestPath)) {
            return null;
        }
        String clientId = singleRequired(request, OAuth2ParameterNames.CLIENT_ID);
        if (request.getParameterValues(OAuth2ParameterNames.CLIENT_SECRET) != null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(
            clientId,
            ClientAuthenticationMethod.NONE,
            null,
            Map.of(SESSION_CLIENT_MARKER, Boolean.TRUE));
    }

    private static String applicationPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        return requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : null;
    }

    private static String singleRequired(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1 || values[0].isBlank()) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return values[0];
    }
}
