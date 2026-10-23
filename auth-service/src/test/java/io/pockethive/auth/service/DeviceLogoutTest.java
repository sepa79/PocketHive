package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "pockethive.auth-service.oauth.issuer=https://ph.example.test/auth-service",
    "pockethive.auth-service.oauth.pocket-hive-browser-redirect-uri=http://localhost:8089/auth/callback",
    "pockethive.auth-service.oauth.pocket-planner-browser-redirect-uri=http://localhost:8774/auth/callback",
    "pockethive.auth-service.oauth.pocket-data-browser-redirect-uri=http://localhost:8785/auth/callback"
})
@AutoConfigureMockMvc
class DeviceLogoutTest {
    private static final String ISSUER = "https://ph.example.test/auth-service";
    private static final String ADMIN_ID = "11111111-1111-1111-1111-111111111111";
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void state(DynamicPropertyRegistry registry) {
        registry.add("pockethive.auth-service.oauth.dynamic-client-state-path", () -> directory.resolve("clients.json").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.DATA_CLIENT_ID,
        PocketHiveDeviceAuth.PLANNER_CLIENT_ID})
    void discoveryUsesOnlyCanonicalIssuerForRegisteredDeviceClients(String clientId) throws Exception {
        var result = mvc.perform(get(PocketHiveDeviceAuth.CONFIG_PATH).param("client_id", clientId)
                .header("Host", "attacker.invalid:9999")
                .header("Forwarded", "host=attacker.invalid;proto=http")
                .header("X-Forwarded-Host", "attacker.invalid")
                .header("X-Forwarded-Proto", "http"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.logoutUri").value(ISSUER + PocketHiveDeviceAuth.LOGOUT_PATH + "?client_id=" + clientId))
            .andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(1);
    }

    @Test
    void getRendersConfirmationWithoutEndingCentralLogin() throws Exception {
        MockHttpSession session = login();
        var result = mvc.perform(get(PocketHiveDeviceAuth.LOGOUT_PATH).session(session)
                .param("client_id", PocketHiveDeviceAuth.DATA_CLIENT_ID))
            .andExpect(status().isOk()).andExpect(authenticated().withUsername(ADMIN_ID))
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Location")).andReturn();
        assertThat(session.isInvalid()).isFalse();
        assertThat(result.getResponse().getContentAsString())
            .contains("Sign out of shared login?", "PocketData", "method=\"post\"",
                "action=\"" + ISSUER + PocketHiveDeviceAuth.LOGOUT_PATH + "\"", "name=\"_csrf\"")
            .doesNotContain("/auth/callback", "redirect_uri");
    }

    @Test
    void missingOrInvalidCsrfCannotEndCentralLogin() throws Exception {
        MockHttpSession session = login();
        mvc.perform(post(PocketHiveDeviceAuth.LOGOUT_PATH).session(session)
                .param("client_id", PocketHiveDeviceAuth.DATA_CLIENT_ID))
            .andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(post(PocketHiveDeviceAuth.LOGOUT_PATH).session(session).with(csrf().useInvalidToken())
                .param("client_id", PocketHiveDeviceAuth.DATA_CLIENT_ID))
            .andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get(PocketHiveDeviceAuth.LOGOUT_PATH).session(session)
                .param("client_id", PocketHiveDeviceAuth.DATA_CLIENT_ID))
            .andExpect(status().isOk()).andExpect(authenticated().withUsername(ADMIN_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.DATA_CLIENT_ID,
        PocketHiveDeviceAuth.PLANNER_CLIENT_ID})
    void confirmedPostClearsCentralContextAndSessionWithFixedCompletion(String clientId) throws Exception {
        MockHttpSession session = login();
        var confirmation = mvc.perform(get(PocketHiveDeviceAuth.LOGOUT_PATH).session(session)
                .param("client_id", clientId)).andExpect(status().isOk()).andReturn();
        CsrfToken csrfToken = (CsrfToken) confirmation.getRequest().getAttribute(CsrfToken.class.getName());
        var result = mvc.perform(post(PocketHiveDeviceAuth.LOGOUT_PATH).session(session)
                .param("client_id", clientId).param(csrfToken.getParameterName(), csrfToken.getToken())
                .param("returnUrl", "https://attacker.invalid/return")
                .param("redirect_uri", "http://localhost:12345/auth/callback"))
            .andExpect(status().isOk()).andExpect(unauthenticated())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Location")).andReturn();
        assertThat(session.isInvalid()).isTrue();
        assertThat(result.getResponse().getContentAsString())
            .contains("Shared login signed out", "You can close this tab.", "Other already signed-in tools")
            .doesNotContain("attacker.invalid", "12345", "callback", "<form", "<script");
        mvc.perform(get(PocketHiveDeviceAuth.CONSENT_PATH))
            .andExpect(status().is3xxRedirection()).andExpect(unauthenticated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "pockethive-web", "pocketplanner-web", "pocketdata-web"})
    void invalidOrRedirectClientCannotUseDeviceLogout(String clientId) throws Exception {
        MockHttpSession session = login();
        mvc.perform(get(PocketHiveDeviceAuth.CONFIG_PATH).param("client_id", clientId))
            .andExpect(status().isBadRequest());
        mvc.perform(get(PocketHiveDeviceAuth.LOGOUT_PATH).session(session).param("client_id", clientId))
            .andExpect(status().isBadRequest());
        mvc.perform(post(PocketHiveDeviceAuth.LOGOUT_PATH).session(session).with(csrf()).param("client_id", clientId))
            .andExpect(status().isBadRequest());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get(PocketHiveDeviceAuth.LOGOUT_PATH).session(session)
                .param("client_id", PocketHiveDeviceAuth.DATA_CLIENT_ID))
            .andExpect(authenticated().withUsername(ADMIN_ID));
    }

    @Test
    void missingClientIsExplicitlyRejectedOnEveryEndpoint() throws Exception {
        mvc.perform(get(PocketHiveDeviceAuth.CONFIG_PATH)).andExpect(status().isBadRequest());
        mvc.perform(get(PocketHiveDeviceAuth.LOGOUT_PATH)).andExpect(status().isBadRequest());
        mvc.perform(post(PocketHiveDeviceAuth.LOGOUT_PATH).with(csrf())).andExpect(status().isBadRequest());
    }

    @Test
    void alreadySignedOutBrowserCanConfirmWithoutANewLogin() throws Exception {
        var confirmation = mvc.perform(get(PocketHiveDeviceAuth.LOGOUT_PATH)
                .param("client_id", PocketHiveDeviceAuth.PLANNER_CLIENT_ID))
            .andExpect(status().isOk()).andExpect(unauthenticated()).andReturn();
        CsrfToken csrfToken = (CsrfToken) confirmation.getRequest().getAttribute(CsrfToken.class.getName());
        MockHttpSession session = (MockHttpSession) confirmation.getRequest().getSession(false);
        mvc.perform(post(PocketHiveDeviceAuth.LOGOUT_PATH).session(session)
                .param("client_id", PocketHiveDeviceAuth.PLANNER_CLIENT_ID)
                .param(csrfToken.getParameterName(), csrfToken.getToken()))
            .andExpect(status().isOk()).andExpect(unauthenticated()).andExpect(header().doesNotExist("Location"));
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/oauth/dev/login").session(session).with(csrf()).param("username", "local-admin"))
            .andExpect(status().is3xxRedirection()).andExpect(authenticated().withUsername(ADMIN_ID));
        return session;
    }
}
