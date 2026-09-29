package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class LocalhostLoopbackRedirectValidatorTest {
    private static final String REGISTERED_REDIRECT = "http://localhost:52000/oauth/callback";
    private static final String RUNTIME_REDIRECT = "http://localhost:62810/oauth/callback";

    @Test
    void revalidatesPortVariationWithAnIsolatedRuntimeRedirectProjection() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<OAuth2AuthorizationCodeRequestAuthenticationContext> retried =
            new AtomicReference<>();
        Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> delegate = context -> {
            if (calls.incrementAndGet() == 1) {
                throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_REQUEST, "redirect mismatch", null));
            }
            retried.set(context);
        };
        OAuth2AuthorizationCodeRequestAuthenticationContext original = context(
            REGISTERED_REDIRECT, RUNTIME_REDIRECT);

        new LocalhostLoopbackRedirectValidator(delegate).accept(original);

        assertThat(calls).hasValue(2);
        assertThat(retried.get()).isNotNull().isNotSameAs(original);
        assertThat(retried.get().getRegisteredClient()).isNotSameAs(original.getRegisteredClient());
        assertThat(retried.get().getRegisteredClient().getRedirectUris())
            .contains(REGISTERED_REDIRECT, RUNTIME_REDIRECT);
        assertThat(original.getRegisteredClient().getRedirectUris())
            .containsExactly(REGISTERED_REDIRECT);
        assertThat(retried.get().getAuthorizationRequest())
            .isSameAs(original.getAuthorizationRequest());
        assertThat(retried.get().getAuthorizationConsent())
            .isSameAs(original.getAuthorizationConsent());
    }

    @Test
    void preservesCanonicalFailureForMalformedRuntimeRedirect() {
        AtomicInteger calls = new AtomicInteger();
        OAuth2AuthenticationException failure = new OAuth2AuthenticationException(new OAuth2Error(
            OAuth2ErrorCodes.INVALID_REQUEST, "redirect mismatch", null));
        Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> delegate = context -> {
            calls.incrementAndGet();
            throw failure;
        };

        assertThatThrownBy(() -> new LocalhostLoopbackRedirectValidator(delegate)
                .accept(context(REGISTERED_REDIRECT, "http://[invalid")))
            .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryAnExactRedirectFailure() {
        AtomicInteger calls = new AtomicInteger();
        OAuth2AuthenticationException failure = new OAuth2AuthenticationException(new OAuth2Error(
            OAuth2ErrorCodes.INVALID_SCOPE, "scope mismatch", null));
        Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> delegate = context -> {
            calls.incrementAndGet();
            throw failure;
        };

        assertThatThrownBy(() -> new LocalhostLoopbackRedirectValidator(delegate)
                .accept(context(REGISTERED_REDIRECT, REGISTERED_REDIRECT)))
            .isSameAs(failure);
        assertThat(calls).hasValue(1);
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationContext context(
        String registeredRedirect,
        String requestedRedirect
    ) {
        RegisteredClient client = RegisteredClient.withId("amazon-q")
            .clientId("amazon-q")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(registeredRedirect)
            .scope("pockethive:mcp:read")
            .build();
        OAuth2AuthorizationCodeRequestAuthenticationToken request =
            new OAuth2AuthorizationCodeRequestAuthenticationToken(
                "http://localhost:8088/auth-service/oauth/authorize",
                client.getClientId(),
                new TestingAuthenticationToken("local-admin", null),
                requestedRedirect,
                "state",
                Set.of("pockethive:mcp:read"),
                Map.of());
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("http://localhost:8088/auth-service/oauth/authorize")
            .clientId(client.getClientId())
            .redirectUri(requestedRedirect)
            .scopes(Set.of("pockethive:mcp:read"))
            .state("state")
            .build();
        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
            .withId(client.getId(), "local-admin")
            .scope("pockethive:mcp:read")
            .build();
        return OAuth2AuthorizationCodeRequestAuthenticationContext.with(request)
            .registeredClient(client)
            .authorizationRequest(authorizationRequest)
            .authorizationConsent(consent)
            .build();
    }
}
