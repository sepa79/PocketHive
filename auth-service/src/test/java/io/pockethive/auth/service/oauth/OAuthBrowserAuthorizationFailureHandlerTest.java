package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.auth.service.config.AuthServiceProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

class OAuthBrowserAuthorizationFailureHandlerTest {

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

    private static OAuthBrowserAuthorizationFailureHandler handler() {
        AuthServiceProperties properties = new AuthServiceProperties();
        properties.getOauth().setIssuer(URI.create("https://pockethive.example/auth-service"));
        return new OAuthBrowserAuthorizationFailureHandler(new OAuthBrowserPageRenderer(), properties);
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
