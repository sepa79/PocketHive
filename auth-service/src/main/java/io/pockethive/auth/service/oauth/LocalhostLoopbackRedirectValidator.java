package io.pockethive.auth.service.oauth;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * Responsibility: Adapt bounded localhost callback port rotation before canonical authorization validation.
 * Must not: Relax redirect scheme, host, path, query, fragment, user-info, or non-loopback matching.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */
final class LocalhostLoopbackRedirectValidator
    implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {
    private final Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> delegate;

    LocalhostLoopbackRedirectValidator(
        Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> delegate
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        try {
            delegate.accept(context);
        } catch (OAuth2AuthenticationException exception) {
            if (!matchesRegisteredLocalhost(context)) throw exception;
            delegate.accept(withRuntimeRedirect(context));
        }
    }

    private static boolean matchesRegisteredLocalhost(
        OAuth2AuthorizationCodeRequestAuthenticationContext context
    ) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        URI requested = parse(request.getRedirectUri());
        if (!boundedLocalhost(requested, true)) return false;
        return context.getRegisteredClient().getRedirectUris().stream()
            .map(LocalhostLoopbackRedirectValidator::parse)
            .anyMatch(registered -> sameExceptPort(registered, requested));
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationContext withRuntimeRedirect(
        OAuth2AuthorizationCodeRequestAuthenticationContext context
    ) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        RegisteredClient client = RegisteredClient.from(context.getRegisteredClient())
            .redirectUri(request.getRedirectUri())
            .build();
        var builder = OAuth2AuthorizationCodeRequestAuthenticationContext.with(request)
            .registeredClient(client);
        if (context.getAuthorizationRequest() != null) {
            builder.authorizationRequest(context.getAuthorizationRequest());
        }
        if (context.getAuthorizationConsent() != null) {
            builder.authorizationConsent(context.getAuthorizationConsent());
        }
        return builder.build();
    }

    private static boolean boundedLocalhost(URI uri, boolean requirePort) {
        if (uri == null || !uri.isAbsolute() || !"http".equalsIgnoreCase(uri.getScheme())
            || !"localhost".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
            || uri.getFragment() != null) {
            return false;
        }
        int port = uri.getPort();
        return requirePort ? validPort(port) : port == -1 || validPort(port);
    }

    private static boolean validPort(int port) {
        return port >= 1 && port <= 65_535;
    }

    private static boolean sameExceptPort(URI registered, URI requested) {
        return boundedLocalhost(registered, false)
            && registered.getScheme().equalsIgnoreCase(requested.getScheme())
            && registered.getHost().equalsIgnoreCase(requested.getHost())
            && registered.getPort() != requested.getPort()
            && Objects.equals(registered.getRawPath(), requested.getRawPath())
            && Objects.equals(registered.getRawQuery(), requested.getRawQuery());
    }

    private static URI parse(String value) {
        if (value == null) return null;
        try {
            return URI.create(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
