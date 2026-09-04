package io.pockethive.auth.service.oauth;

import io.pockethive.auth.service.config.AuthServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Render bounded PocketHive browser responses for authorization-endpoint failures.
 * Must not: Echo request parameters or exception descriptions, redirect to untrusted URIs, or alter OAuth policy.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */
@Component
final class OAuthBrowserAuthorizationFailureHandler implements AuthenticationFailureHandler {
    private static final String AUTHORIZATION_ERROR = "authorization_error";
    private final OAuthBrowserPageRenderer pages;
    private final String stylesheet;
    private final String logo;

    OAuthBrowserAuthorizationFailureHandler(OAuthBrowserPageRenderer pages, AuthServiceProperties properties) {
        this.pages = pages;
        String issuer = properties.getOauth().getIssuer().toString();
        this.stylesheet = issuer + OAuthBrowserController.STYLESHEET_PATH;
        this.logo = issuer + OAuthBrowserController.LOGO_PATH;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String code = safeCode(exception);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(pages.authorizationFailure(
            code, safeMessage(code), stylesheet, logo));
    }

    private static String safeCode(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth)) return AUTHORIZATION_ERROR;
        String code = oauth.getError().getErrorCode();
        return switch (code) {
            case OAuth2ErrorCodes.INVALID_CLIENT,
                 OAuth2ErrorCodes.INVALID_REQUEST,
                 OAuth2ErrorCodes.INVALID_SCOPE,
                 OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE,
                 OAuth2ErrorCodes.ACCESS_DENIED -> code;
            default -> AUTHORIZATION_ERROR;
        };
    }

    private static String safeMessage(String code) {
        return switch (code) {
            case OAuth2ErrorCodes.INVALID_CLIENT ->
                "This client registration is not recognized by the selected PocketHive environment.";
            case OAuth2ErrorCodes.INVALID_SCOPE ->
                "The client requested a permission that this PocketHive environment cannot grant.";
            case OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE ->
                "The client requested an authorization response type that is not supported.";
            case OAuth2ErrorCodes.ACCESS_DENIED -> "The authorization request was declined.";
            case OAuth2ErrorCodes.INVALID_REQUEST ->
                "The authorization request is incomplete, invalid, or refers to an unrecognized client registration.";
            default -> "PocketHive could not safely complete this authorization request.";
        };
    }
}
