package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Responsibility: Decode explicit public device-client authentication requests.
 * Must not: Authenticate users, validate consent or issue authorization results.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md.
 */
final class DeviceClientAuthenticationConverter implements AuthenticationConverter {
    static final String MARKER = DeviceClientAuthenticationConverter.class.getName();

    @Override
    public Authentication convert(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean device = PocketHiveDeviceAuth.AUTHORIZATION_PATH.equals(path)
            || ("/oauth/token".equals(path)
                && AuthorizationGrantType.DEVICE_CODE.getValue().equals(request.getParameter("grant_type")));
        if (!device || !"POST".equals(request.getMethod()) || request.getHeader("Authorization") != null) return null;
        String[] values = request.getParameterValues("client_id");
        if (values == null || values.length != 1 || values[0].isBlank()
            || request.getParameterValues("client_secret") != null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(values[0], ClientAuthenticationMethod.NONE, null,
            Map.of(MARKER, Boolean.TRUE));
    }
}
