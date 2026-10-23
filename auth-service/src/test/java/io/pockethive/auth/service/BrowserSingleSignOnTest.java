package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties = {
    "pockethive.auth-service.oauth.pocket-hive-browser-redirect-uri=http://localhost:8089/auth/callback",
    "pockethive.auth-service.oauth.pocket-planner-browser-redirect-uri=http://localhost:8774/auth/callback",
    "pockethive.auth-service.oauth.pocket-data-browser-redirect-uri=http://localhost:8785/auth/callback"
})
@AutoConfigureMockMvc
class BrowserSingleSignOnTest {
    private static final String PH = "http://localhost:8089/auth/callback";
    private static final String PP = "http://localhost:8774/auth/callback";
    private static final String PD = "http://localhost:8785/auth/callback";
    private static final String VERIFIER = "browser-test-verifier-with-more-than-forty-three-characters";
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OAuth2AuthorizationService authorizations;

    @DynamicPropertySource
    static void state(DynamicPropertyRegistry registry) {
        registry.add("pockethive.auth-service.oauth.dynamic-client-state-path", () -> directory.resolve("clients.json").toString());
    }

    @Test
    void oneCentralLoginAuthorizesAllThreeToolsWithDistinctRevocableTokens() throws Exception {
        MockHttpSession session = login("local-admin");
        JsonNode planner = exchange(PocketHiveBrowserAuth.PP_CLIENT_ID, PP,
            code(authorize(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, session)), VERIFIER);
        JsonNode hive = exchange(PocketHiveBrowserAuth.PH_CLIENT_ID, PH,
            code(authorize(PocketHiveBrowserAuth.PH_CLIENT_ID, PH, session)), VERIFIER);
        JsonNode data = exchange(PocketHiveBrowserAuth.PD_CLIENT_ID, PD,
            code(authorize(PocketHiveBrowserAuth.PD_CLIENT_ID, PD, session)), VERIFIER);
        assertThat(planner.path("access_token").asText()).startsWith("phweb_")
            .isNotEqualTo(hive.path("access_token").asText());
        assertThat(planner.has("refresh_token")).isFalse();
        assertThat(data.path("access_token").asText()).startsWith("phweb_")
            .isNotEqualTo(planner.path("access_token").asText())
            .isNotEqualTo(hive.path("access_token").asText());
        assertThat(data.has("refresh_token")).isFalse();
        for (JsonNode token : new JsonNode[]{planner, hive, data}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token.path("access_token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("local-admin"));
        }
        mvc.perform(post("/oauth/revoke").param("client_id", PocketHiveBrowserAuth.PP_CLIENT_ID)
                .param("token_type_hint", "access_token").param("token", planner.path("access_token").asText()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + planner.path("access_token").asText()))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/resolve").header("Authorization", "Bearer " + hive.path("access_token").asText()))
            .andExpect(status().isOk());
    }

    @Test
    void viewerAuthenticatesWithoutGainingAdminGrants() throws Exception {
        JsonNode token = exchange(PocketHiveBrowserAuth.PH_CLIENT_ID, PH,
            code(authorize(PocketHiveBrowserAuth.PH_CLIENT_ID, PH, login("local-viewer"))), VERIFIER);
        mvc.perform(get("/api/auth/access").header("Authorization", "Bearer " + token.path("access_token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canRunPocketHive").value(false))
            .andExpect(jsonPath("$.canManageUsers").value(false));
    }

    @Test
    void requiresLoginWithoutCentralSessionAndPublishesOnlyRegisteredConfig() throws Exception {
        MvcResult result = authorize(PocketHiveBrowserAuth.PH_CLIENT_ID, PH, new MockHttpSession());
        assertThat(result.getResponse().getRedirectedUrl()).contains("/oauth/dev/login");
        mvc.perform(get("/api/auth/browser/config").param("client_id", PocketHiveBrowserAuth.PP_CLIENT_ID))
            .andExpect(status().isOk()).andExpect(jsonPath("$.redirectUri").value(PP))
            .andExpect(jsonPath("$.resource").value(PocketHiveBrowserAuth.RESOURCE));
        mvc.perform(get("/api/auth/browser/config").param("client_id", "unregistered"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/browser/config").param("client_id", PocketHiveBrowserAuth.PD_CLIENT_ID))
            .andExpect(status().isOk()).andExpect(jsonPath("$.redirectUri").value(PD))
            .andExpect(jsonPath("$.scope").value(PocketHiveBrowserAuth.SCOPE));
    }

    @Test
    void pocketDataRequiresPkceAndItsOwnExactCallbackAndCode() throws Exception {
        MockHttpSession session = login("local-admin");
        MvcResult wrongPort = authorize(PocketHiveBrowserAuth.PD_CLIENT_ID,
            "http://localhost:9999/auth/callback", session);
        assertThat(wrongPort.getResponse().getRedirectedUrl()).isNull();
        assertThat(wrongPort.getResponse().getStatus()).isBetween(400, 499);
        MvcResult missingPkce = mvc.perform(authorizationRequest(PocketHiveBrowserAuth.PD_CLIENT_ID, PD, session)
            .with(request -> { request.removeParameter("code_challenge"); request.removeParameter("code_challenge_method"); return request; }))
            .andReturn();
        assertThat(missingPkce.getResponse().getStatus()).isBetween(400, 499);
        assertThat(missingPkce.getResponse().getRedirectedUrl()).isNull();
        String dataCode = code(authorize(PocketHiveBrowserAuth.PD_CLIENT_ID, PD, session));
        mvc.perform(tokenRequest(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, dataCode, VERIFIER))
            .andExpect(status().isBadRequest());
        String valid = code(authorize(PocketHiveBrowserAuth.PD_CLIENT_ID, PD, session));
        exchange(PocketHiveBrowserAuth.PD_CLIENT_ID, PD, valid, VERIFIER);
        mvc.perform(tokenRequest(PocketHiveBrowserAuth.PD_CLIENT_ID, PD, valid, VERIFIER))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsWrongVerifierAndConsumedCode() throws Exception {
        String code = code(authorize(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, login("local-admin")));
        mvc.perform(tokenRequest(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, code, "wrong-verifier-but-long-enough-for-a-valid-pkce-syntax"))
            .andExpect(status().isBadRequest());
        String valid = code(authorize(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, login("local-admin")));
        exchange(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, valid, VERIFIER);
        mvc.perform(tokenRequest(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, valid, VERIFIER)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCrossClientCodeAndWrongResource() throws Exception {
        String code = code(authorize(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, login("local-admin")));
        mvc.perform(tokenRequest(PocketHiveBrowserAuth.PH_CLIENT_ID, PH, code, VERIFIER)).andExpect(status().isBadRequest());
        mvc.perform(tokenRequest(PocketHiveBrowserAuth.PP_CLIENT_ID, PP, code, VERIFIER)
                .with(request -> { request.setParameter("resource", "http://localhost:8080/mcp"); return request; }))
            .andExpect(status().isBadRequest());
    }

    @Test
    void browserCallbackPortIsExactAndMcpScopesAreNotBrowserIdentity() throws Exception {
        MvcResult wrongPort = authorize(PocketHiveBrowserAuth.PH_CLIENT_ID, "http://localhost:9999/auth/callback", login("local-admin"));
        assertThat(wrongPort.getResponse().getRedirectedUrl()).isNull();
        assertThat(wrongPort.getResponse().getStatus()).isBetween(400, 499);
        MvcResult scopes = mvc.perform(authorizationRequest(PocketHiveBrowserAuth.PH_CLIENT_ID, PH, login("local-admin"))
                .with(request -> { request.setParameter("scope", "pockethive:mcp:read"); return request; }))
            .andReturn();
        assertThat(scopes.getResponse().getContentAsString()).doesNotContain("phweb_");
        assertThat(scopes.getResponse().getRedirectedUrl() == null
            || scopes.getResponse().getRedirectedUrl().contains("error=")).isTrue();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer phmcp_unknown"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void centralLogoutRequiresCsrfAndReturnsOnlyToConfiguredCallback() throws Exception {
        MockHttpSession session = login("local-admin");
        mvc.perform(post("/oauth/browser/logout").session(session).param("client_id", PocketHiveBrowserAuth.PH_CLIENT_ID))
            .andExpect(status().isForbidden());
        mvc.perform(post("/oauth/browser/logout").session(session).with(csrf())
                .param("client_id", PocketHiveBrowserAuth.PH_CLIENT_ID))
            .andExpect(status().is3xxRedirection());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/oauth/browser/logout").param("client_id", "https://attacker.invalid"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void expiredTokenInTheCanonicalStoreCannotRestoreIdentity() throws Exception {
        String value = exchange(PocketHiveBrowserAuth.PH_CLIENT_ID, PH,
            code(authorize(PocketHiveBrowserAuth.PH_CLIENT_ID, PH, login("local-admin"))), VERIFIER)
            .path("access_token").asText();
        var existing = authorizations.findByToken(value, OAuth2TokenType.ACCESS_TOKEN);
        var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value,
            Instant.now().minusSeconds(60), Instant.now().minusSeconds(1), existing.getAuthorizedScopes());
        authorizations.save(OAuth2Authorization.from(existing)
            .token(expired, metadata -> metadata.putAll(existing.getAccessToken().getMetadata())).build());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + value)).andExpect(status().isUnauthorized());
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/oauth/dev/login").session(session).with(csrf()).param("username", username))
            .andExpect(status().is3xxRedirection());
        return session;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizationRequest(
        String client, String redirect, MockHttpSession session) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth/authorize").session(session).queryParam("response_type", "code")
            .queryParam("client_id", client).queryParam("redirect_uri", redirect)
            .queryParam("resource", PocketHiveBrowserAuth.RESOURCE).queryParam("scope", PocketHiveBrowserAuth.SCOPE)
            .queryParam("state", "browser-state").queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
    }

    private MvcResult authorize(String client, String redirect, MockHttpSession session) throws Exception {
        return mvc.perform(authorizationRequest(client, redirect, session)).andReturn();
    }

    private String code(MvcResult result) {
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).contains("/auth/callback?").contains("state=browser-state");
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        assertThat(code).isNotBlank();
        return code;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
        String client, String redirect, String code, String verifier) {
        return post("/oauth/token").param("grant_type", "authorization_code").param("client_id", client)
            .param("redirect_uri", redirect).param("resource", PocketHiveBrowserAuth.RESOURCE)
            .param("code", code).param("code_verifier", verifier);
    }

    private JsonNode exchange(String client, String redirect, String code, String verifier) throws Exception {
        return mapper.readTree(mvc.perform(tokenRequest(client, redirect, code, verifier))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
