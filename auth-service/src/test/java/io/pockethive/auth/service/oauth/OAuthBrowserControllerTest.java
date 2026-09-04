package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.domain.StoredUser;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class OAuthBrowserControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disabledDevProviderRendersBoundedPocketHiveFailure() throws Exception {
        AuthServiceProperties properties = new AuthServiceProperties();
        properties.setProvider(AuthProvider.LDAP);
        properties.getOauth().setIssuer(URI.create("https://pockethive.example/auth-service"));
        OAuthBrowserPageRenderer pages = new OAuthBrowserPageRenderer();
        OAuthBrowserAuthorizationFailureHandler failures =
            new OAuthBrowserAuthorizationFailureHandler(pages, properties);
        OAuthBrowserController controller = new OAuthBrowserController(
            properties, mock(InMemoryUserStore.class), pages, failures,
            mock(RegisteredClientRepository.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.loginPage(new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
            .contains("PocketHive", "authorization_error")
            .doesNotContain("Whitelabel");
    }

    @Test
    void successfulDevLoginSetsCurrentAndPersistedSecurityContext() throws Exception {
        AuthServiceProperties properties = properties(AuthProvider.DEV);
        OAuthBrowserPageRenderer pages = new OAuthBrowserPageRenderer();
        OAuthBrowserAuthorizationFailureHandler failures =
            new OAuthBrowserAuthorizationFailureHandler(pages, properties);
        InMemoryUserStore users = mock(InMemoryUserStore.class);
        when(users.findByUsername("local-admin")).thenReturn(Optional.of(new StoredUser(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "local-admin", "Local Admin", true, List.of())));
        OAuthBrowserController controller = new OAuthBrowserController(
            properties, users, pages, failures, mock(RegisteredClientRepository.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.login("local-admin", request, response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
            .isEqualTo("local-admin");
        SecurityContext persisted = (SecurityContext) request.getSession(false).getAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(persisted.getAuthentication().getName()).isEqualTo("local-admin");
    }

    private static AuthServiceProperties properties(AuthProvider provider) {
        AuthServiceProperties properties = new AuthServiceProperties();
        properties.setProvider(provider);
        properties.getOauth().setIssuer(URI.create("https://pockethive.example/auth-service"));
        return properties;
    }
}
