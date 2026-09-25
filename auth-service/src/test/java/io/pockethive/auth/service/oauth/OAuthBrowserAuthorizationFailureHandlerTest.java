package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.auth.service.config.AuthServiceProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.web.util.UriComponentsBuilder;

class OAuthBrowserAuthorizationFailureHandlerTest {

    @Test
    void redirectsOnlyToSpringsValidatedCallbackWithBoundedErrorAndOriginalState() throws Exception {
        String callback = "http://127.0.0.1:38125/callback?existing=encoded%2Bvalue";
        String state = "original +/&% state";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("redirect_uri", "https://untrusted.example/callback");
        request.setParameter("state", "untrusted-state");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationFailure(request, response, authorizationFailure(callback, state));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        URI redirect = URI.create(response.getRedirectedUrl());
        assertThat(redirect.getScheme() + "://" + redirect.getAuthority() + redirect.getPath())
            .isEqualTo("http://127.0.0.1:38125/callback");
        var parameters = UriComponentsBuilder.fromUri(redirect).build().getQueryParams();
        assertThat(parameters.keySet()).containsExactlyInAnyOrder("existing", "error", "state");
        assertThat(parameters.getFirst("existing")).isEqualTo("encoded%2Bvalue");
        assertThat(parameters.getFirst("error")).isEqualTo("access_denied");
        assertThat(URLDecoder.decode(parameters.getFirst("state"), StandardCharsets.UTF_8)).isEqualTo(state);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getRedirectedUrl()).doesNotContain("untrusted", "sensitive-detail");
    }

    @Test
    void validatedCallbackDoesNotInventStateWhenSpringSuppliesNone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationFailure(new MockHttpServletRequest(), response,
            authorizationFailure("http://127.0.0.1:38125/callback", null));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
            .isEqualTo("http://127.0.0.1:38125/callback?error=access_denied");
    }

    @Test
    void unvalidatedAuthorizationContextCannotRedirectEvenWhenRequestSuppliesACallback() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("redirect_uri", "https://untrusted.example/callback");
        request.setParameter("state", "untrusted-state");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationFailure(request, response, authorizationFailure(null, "untrusted-state"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getContentAsString()).contains("PocketHive", "access_denied")
            .doesNotContain("untrusted", "sensitive-detail");
    }

    @Test
    void rendersOnlyBoundedOAuthCodesAndMessages() throws Exception {
        OAuthBrowserAuthorizationFailureHandler handler = handler();
        List<ExpectedFailure> failures = List.of(
            new ExpectedFailure(OAuth2ErrorCodes.INVALID_CLIENT, "registration is not recognized"),
            new ExpectedFailure(OAuth2ErrorCodes.INVALID_REQUEST, "unrecognized client registration"),
            new ExpectedFailure(OAuth2ErrorCodes.INVALID_SCOPE, "permission"),
            new ExpectedFailure(OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE, "response type"),
            new ExpectedFailure(OAuth2ErrorCodes.ACCESS_DENIED, "declined"),
            new ExpectedFailure("attacker-<script>", "safely complete"));

        for (ExpectedFailure expected : failures) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error(
                    expected.code(), "untrusted-<img src=x onerror=alert(1)>", null)));

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getContentAsString())
                .contains("PocketHive", "Authorization could not continue", expected.message())
                .doesNotContain("untrusted-", "attacker-", "<script>");
            String renderedCode = PocketHiveOAuthErrorCodes.contains(expected.code())
                ? expected.code() : "authorization_error";
            assertThat(response.getContentAsString()).contains("<code>" + renderedCode + "</code>");
        }
    }

    @Test
    void rendersGenericFailureForNonOAuthExceptions() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationFailure(new MockHttpServletRequest(), response,
            new BadCredentialsException("untrusted-secret-detail"));

        assertThat(response.getContentAsString())
            .contains("authorization_error", "safely complete")
            .doesNotContain("untrusted-secret-detail");
    }

    @Test
    void rendersSecurityAccessDeniedWithoutDispatchingToTheFrameworkErrorPage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().handle(new MockHttpServletRequest(), response,
            new AccessDeniedException("untrusted-secret-detail"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString())
            .contains("access_denied", "PocketHive")
            .doesNotContain("untrusted-secret-detail", "Whitelabel");
    }

    @Test
    void writesExplicitControllerFailureStatusThroughTheCanonicalRenderer() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().writeFailure(response, OAuth2ErrorCodes.INVALID_REQUEST, HttpStatus.UNAUTHORIZED);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString())
            .contains("invalid_request", "PocketHive")
            .doesNotContain("Whitelabel");
    }

    @Test
    void boundsCodesSuppliedByControllerAdapters() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().writeFailure(response, "attacker-<script>", HttpStatus.BAD_REQUEST);

        assertThat(response.getContentAsString())
            .contains("authorization_error", "safely complete")
            .doesNotContain("attacker-", "<script>");
    }

    private static OAuthBrowserAuthorizationFailureHandler handler() {
        AuthServiceProperties properties = new AuthServiceProperties();
        properties.getOauth().setIssuer(URI.create("https://pockethive.example/auth-service"));
        return new OAuthBrowserAuthorizationFailureHandler(new OAuthBrowserPageRenderer(), properties);
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationException authorizationFailure(
        String redirect, String state
    ) {
        var principal = UsernamePasswordAuthenticationToken.authenticated("test-principal", "N/A", List.of());
        var authorization = new OAuth2AuthorizationCodeRequestAuthenticationToken(
            "https://pockethive.example/auth-service/oauth/authorize", "test-client", principal,
            redirect, state, Set.of("test-scope"), Map.of());
        return new OAuth2AuthorizationCodeRequestAuthenticationException(
            new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED, "sensitive-detail", "https://untrusted.example/error"),
            authorization);
    }

    private record ExpectedFailure(String code, String message) {
    }

    private static final class PocketHiveOAuthErrorCodes {
        private static boolean contains(String code) {
            return List.of(
                OAuth2ErrorCodes.INVALID_CLIENT,
                OAuth2ErrorCodes.INVALID_REQUEST,
                OAuth2ErrorCodes.INVALID_SCOPE,
                OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE,
                OAuth2ErrorCodes.ACCESS_DENIED).contains(code);
        }
    }
}
