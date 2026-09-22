package io.pockethive.auth.service.oauth;

import io.pockethive.auth.service.config.AuthServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Responsibility: Own bounded PocketHive responses for OAuth browser authentication and authorization failures.
 * Must not: Echo request parameters or exception descriptions, redirect to untrusted URIs, or alter OAuth policy.
 * Contract: RESP-OAUTH-BROWSER-FAILURE — docs/architecture/runtime-responsibilities.md#resp-oauth-browser-failure.
 */
@Component
final class OAuthBrowserAuthorizationFailureHandler implements AuthenticationFailureHandler, AccessDeniedHandler {
    static final String AUTHORIZATION_ERROR = "authorization_error";
    private final OAuthBrowserPageRenderer pages;
    private final String stylesheet;
    private final String logo;
    private final DefaultRedirectStrategy redirects = new DefaultRedirectStrategy();

    OAuthBrowserAuthorizationFailureHandler(OAuthBrowserPageRenderer pages, AuthServiceProperties properties) {
        this.pages = pages;
        String issuer = properties.getOauth().getIssuer().toString();
        this.stylesheet = issuer + OAuthBrowserController.STYLESHEET_PATH;
        this.logo = issuer + OAuthBrowserController.LOGO_PATH;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth
            ? oauth.getError().getErrorCode() : AUTHORIZATION_ERROR;
        if (exception instanceof OAuth2AuthorizationCodeRequestAuthenticationException authorizationFailure) {
            var authorization = authorizationFailure.getAuthorizationCodeRequestAuthentication();
            // Spring's provider omits redirectUri until the client/callback and pending consent are trusted.
            if (authorization != null && StringUtils.hasText(authorization.getRedirectUri())) {
                var callback = UriComponentsBuilder.fromUriString(authorization.getRedirectUri())
                    .queryParam(OAuth2ParameterNames.ERROR, safeCode(code));
                if (StringUtils.hasText(authorization.getState())) {
                    callback.queryParam(OAuth2ParameterNames.STATE,
                        UriUtils.encode(authorization.getState(), StandardCharsets.UTF_8));
                }
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                redirects.sendRedirect(request, response, callback.build(true).toUriString());
                return;
            }
        }
        writeFailure(response, code, HttpStatus.BAD_REQUEST);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        writeFailure(response, OAuth2ErrorCodes.ACCESS_DENIED, HttpStatus.FORBIDDEN);
    }

    void writeFailure(HttpServletResponse response, String code, HttpStatus status) throws IOException {
        String boundedCode = safeCode(code);
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(pages.authorizationFailure(
            boundedCode, safeMessage(boundedCode), stylesheet, logo));
    }

    private static String safeCode(String code) {
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
            case OAuth2ErrorCodes.ACCESS_DENIED ->
                "The authorization request was declined or could not be verified.";
            case OAuth2ErrorCodes.INVALID_REQUEST ->
                "The authorization request is incomplete, invalid, or refers to an unrecognized client registration.";
            default -> "PocketHive could not safely complete this authorization request.";
        };
    }
}
