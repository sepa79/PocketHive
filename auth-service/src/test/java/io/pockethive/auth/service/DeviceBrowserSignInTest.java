package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceBrowserSignInTest {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations;

    @DynamicPropertySource
    static void state(DynamicPropertyRegistry registry) {
        registry.add("pockethive.auth-service.oauth.dynamic-client-state-path", () -> directory.resolve("clients.json").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void pendingUntilConfirmedThenIssuesUsableIdentityWithoutAnyRedirectUri(String client) throws Exception {
        JsonNode device = start(client);
        assertThat(device.path("verification_uri_complete").asText()).contains("user_code=")
            .doesNotContain("redirect_uri", "device_code");
        mvc.perform(poll(client, device.path("device_code").asText()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("authorization_pending"));

        JsonNode approved = start(client);
        confirm(approved, client, login("local-admin"));
        var response = mvc.perform(poll(client, approved.path("device_code").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value(PocketHiveBrowserAuth.SCOPE)).andReturn();
        JsonNode token = mapper.readTree(response.getResponse().getContentAsString());
        assertThat(token.path("access_token").asText()).startsWith(PocketHiveBrowserAuth.TOKEN_PREFIX);
        assertThat(token.has("refresh_token")).isFalse();
        var authorization = authorizations.findByToken(token.path("access_token").asText(),
            org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
        assertThat(authorization.getRegisteredClientId()).isEqualTo("browser-" + client);
        assertThat(authorization.getAuthorizedScopes()).containsExactly(PocketHiveBrowserAuth.SCOPE);
        assertThat(authorization.getAccessToken().getClaims().get("aud"))
            .isEqualTo(List.of(PocketHiveBrowserAuth.RESOURCE));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token.path("access_token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("local-admin"));
        mvc.perform(poll(client, approved.path("device_code").asText()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void allThreeProductsReuseTheSameCentralIdentityWithIndependentlyRevocableTokens() throws Exception {
        MockHttpSession session = login("local-viewer");
        Map<String, String> tokens = new LinkedHashMap<>();
        List<JsonNode> identities = new ArrayList<>();
        for (String client : new String[]{PocketHiveDeviceAuth.HIVE_CLIENT_ID,
            PocketHiveDeviceAuth.DATA_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID}) {
            JsonNode device = start(client);
            confirm(device, client, session);
            var result = mvc.perform(poll(client, device.path("device_code").asText())).andExpect(status().isOk()).andReturn();
            JsonNode token = mapper.readTree(result.getResponse().getContentAsString());
            String value = token.path("access_token").asText();
            tokens.put(client, value);
            var identity = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + value))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("local-viewer")).andReturn();
            identities.add(mapper.readTree(identity.getResponse().getContentAsString()));
            mvc.perform(get("/api/auth/access").header("Authorization", "Bearer " + value))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canRunPocketHive").value(false))
                .andExpect(jsonPath("$.canManageUsers").value(false));
        }
        assertThat(identities).containsOnly(identities.getFirst());
        assertThat(tokens.values()).doesNotHaveDuplicates();
        mvc.perform(post("/oauth/revoke").param("client_id", PocketHiveDeviceAuth.HIVE_CLIENT_ID)
                .param("token_type_hint", "access_token").param("token", tokens.get(PocketHiveDeviceAuth.HIVE_CLIENT_ID)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokens.get(PocketHiveDeviceAuth.HIVE_CLIENT_ID)))
            .andExpect(status().isUnauthorized());
        for (String client : new String[]{PocketHiveDeviceAuth.DATA_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID}) {
            mvc.perform(post("/api/auth/resolve").header("Authorization", "Bearer " + tokens.get(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("local-viewer"));
        }
    }

    @Test
    void rejectsUnregisteredClients() throws Exception {
        mvc.perform(startRequest("unregistered")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void rejectsWrongScopesResourceAndCrossClientPolling(String client) throws Exception {
        mvc.perform(startRequest(client)
                .with(request -> { request.setParameter("scope", "pockethive:mcp:read"); return request; }))
            .andExpect(status().isBadRequest());
        mvc.perform(startRequest(client)
                .with(request -> { request.setParameter("resource", "https://wrong.invalid"); return request; }))
            .andExpect(status().isBadRequest());
        for (String otherClient : new String[]{PocketHiveDeviceAuth.HIVE_CLIENT_ID,
            PocketHiveDeviceAuth.DATA_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID}) {
            if (otherClient.equals(client)) continue;
            JsonNode device = start(client);
            mvc.perform(poll(otherClient, device.path("device_code").asText()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        }
        JsonNode device = start(client);
        mvc.perform(poll(client, device.path("device_code").asText())
                .with(request -> { request.setParameter("resource", "https://wrong.invalid"); return request; }))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void confirmationRequiresLoginAndCsrf(String client) throws Exception {
        JsonNode device = start(client);
        mvc.perform(get(PocketHiveDeviceAuth.VERIFICATION_PATH).queryParam("user_code", device.path("user_code").asText()))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post(PocketHiveDeviceAuth.VERIFICATION_PATH).session(login("local-admin"))
                .param("client_id", client)
                .param("user_code", device.path("user_code").asText()).param("state", "invalid"))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void deviceClientsCannotUseBrowserCallbacks(String client) throws Exception {
        mvc.perform(get("/api/auth/browser/config").param("client_id", client))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/oauth/browser/logout").param("client_id", client))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/oauth/authorize").session(login("local-admin"))
                .param("response_type", "code").param("client_id", client)
                .param("redirect_uri", "http://localhost:12345/auth/callback")
                .param("resource", PocketHiveBrowserAuth.RESOURCE).param("scope", PocketHiveBrowserAuth.SCOPE)
                .param("state", "device-callback-attempt").param("code_challenge_method", "S256")
                .param("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
            .andExpect(status().is4xxClientError()).andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void expiredDeviceCodeCannotBecomeAnIdentity(String client) throws Exception {
        JsonNode device = start(client);
        String value = device.path("device_code").asText();
        var authorization = authorizations.findByToken(value,
            new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("device_code"));
        var expired = new org.springframework.security.oauth2.core.OAuth2DeviceCode(value,
            java.time.Instant.now().minusSeconds(60), java.time.Instant.now().minusSeconds(1));
        authorizations.save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(authorization)
            .token(expired).build());
        mvc.perform(poll(client, value)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("expired_token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void declinedConfirmationNeverIssuesAToken(String client) throws Exception {
        JsonNode device = start(client);
        MockHttpSession session = login("local-runner");
        var verification = mvc.perform(get(PocketHiveDeviceAuth.VERIFICATION_PATH).session(session)
            .queryParam("user_code", device.path("user_code").asText())).andReturn();
        var parameters = UriComponentsBuilder.fromUriString(verification.getResponse().getRedirectedUrl()).build().getQueryParams();
        mvc.perform(post(PocketHiveDeviceAuth.VERIFICATION_PATH).session(session).with(csrf())
                .param("client_id", client).param("state", java.net.URLDecoder.decode(
                    parameters.getFirst("state"), java.nio.charset.StandardCharsets.UTF_8))
                .param("user_code", device.path("user_code").asText()))
            .andExpect(status().isBadRequest());
        mvc.perform(poll(client, device.path("device_code").asText())).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("access_denied"));
    }

    private MockHttpServletRequestBuilder startRequest(String client) {
        return post(PocketHiveDeviceAuth.AUTHORIZATION_PATH).param("client_id", client)
            .param("resource", PocketHiveBrowserAuth.RESOURCE).param("scope", PocketHiveBrowserAuth.SCOPE);
    }

    private JsonNode start(String client) throws Exception {
        return mapper.readTree(mvc.perform(startRequest(client)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder poll(String client, String code) {
        return post("/oauth/token").param("client_id", client)
            .param("grant_type", AuthorizationGrantType.DEVICE_CODE.getValue())
            .param("device_code", code).param("resource", PocketHiveBrowserAuth.RESOURCE);
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/oauth/dev/login").session(session).with(csrf()).param("username", username))
            .andExpect(status().is3xxRedirection());
        return session;
    }

    private void confirm(JsonNode device, String client, MockHttpSession session) throws Exception {
        var verification = mvc.perform(get(PocketHiveDeviceAuth.VERIFICATION_PATH).session(session)
            .queryParam("user_code", device.path("user_code").asText())).andExpect(status().is3xxRedirection()).andReturn();
        String location = verification.getResponse().getRedirectedUrl();
        assertThat(location).contains(PocketHiveDeviceAuth.CONSENT_PATH);
        var parameters = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        mvc.perform(get(PocketHiveDeviceAuth.CONSENT_PATH).session(session)
                .param("client_id", client).param("state", java.net.URLDecoder.decode(
                    parameters.getFirst("state"), java.nio.charset.StandardCharsets.UTF_8))
                .param("user_code", device.path("user_code").asText()).param("scope", PocketHiveBrowserAuth.SCOPE))
            .andExpect(status().isOk());
        var confirmation = mvc.perform(post(PocketHiveDeviceAuth.VERIFICATION_PATH).session(session).with(csrf())
                .param("client_id", client).param("state", java.net.URLDecoder.decode(
                    parameters.getFirst("state"), java.nio.charset.StandardCharsets.UTF_8))
                .param("user_code", device.path("user_code").asText()).param("scope", PocketHiveBrowserAuth.SCOPE))
            .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(confirmation.getResponse().getRedirectedUrl()).endsWith(PocketHiveDeviceAuth.COMPLETE_PATH)
            .doesNotContain("callback", "redirect_uri", "device_code", "access_token");
    }
}
