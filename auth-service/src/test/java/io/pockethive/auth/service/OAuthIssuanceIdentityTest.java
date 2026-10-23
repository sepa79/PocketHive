package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class OAuthIssuanceIdentityTest {
    private static final String MCP_CLIENT = "pockethive-vscode";
    private static final String MCP_RESOURCE = "http://localhost:8080/mcp";
    private static final String REDIRECT_URI = "http://127.0.0.1:38125/callback";
    private static final String VERIFIER = "test-verifier-that-is-at-least-forty-three-characters-long";
    private static final String MCP_SCOPES = PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ;

    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private UUID originalId;
    private String originalUsername;
    private String renamedUsername;
    private String adminBearer;
    private List<AuthGrantDto> currentGrants;

    @DynamicPropertySource
    static void state(DynamicPropertyRegistry registry) {
        registry.add("pockethive.auth-service.oauth.dynamic-client-state-path",
            () -> directory.resolve("clients.json").toString());
    }

    @BeforeEach
    void createDistinctUsersThroughAdminApi() throws Exception {
        var login = mvc.perform(post("/api/auth/dev/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", "local-admin"))))
            .andExpect(status().isOk()).andReturn();
        adminBearer = "Bearer " + mapper.readTree(login.getResponse().getContentAsString())
            .path("accessToken").asText();
        originalId = UUID.randomUUID();
        originalUsername = "issuance-" + originalId;
        renamedUsername = originalUsername + "-renamed";
        upsert(originalId, originalUsername, true);
        grants(originalId, List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.DEPLOYMENT, "*")));
        currentGrants = List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.DEPLOYMENT, "current-a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void confirmedDeviceIssuesOriginalIdentityAndCurrentGrantsAfterUsernameReassignment(String client) throws Exception {
        JsonNode device = startDevice(client);
        confirmDevice(device, client, login(originalUsername));
        reassignUsername(true);

        JsonNode token = response(mvc.perform(pollDevice(client, device.path("device_code").asText()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertBrowserIdentity(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {PocketHiveDeviceAuth.HIVE_CLIENT_ID, PocketHiveDeviceAuth.PLANNER_CLIENT_ID,
        PocketHiveDeviceAuth.DATA_CLIENT_ID})
    void confirmedDeviceRejectsDisabledOriginalUserAfterUsernameReassignment(String client) throws Exception {
        JsonNode device = startDevice(client);
        confirmDevice(device, client, login(originalUsername));
        reassignUsername(false);

        mvc.perform(pollDevice(client, device.path("device_code").asText()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"))
            .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void codeAndRefreshIssueOriginalIdentityAndCurrentGrantsAfterUsernameReassignment(boolean refresh) throws Exception {
        String code = authorizeMcp(login(originalUsername));
        String refreshToken = refresh ? exchange(code).path("refresh_token").asText() : null;
        reassignUsername(true);

        JsonNode token = response(mvc.perform(refresh ? refreshRequest(refreshToken) : exchangeRequest(code))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertMcpIdentity(token);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void codeAndRefreshRejectDisabledOriginalUserAfterUsernameReassignment(boolean refresh) throws Exception {
        String code = authorizeMcp(login(originalUsername));
        String refreshToken = refresh ? exchange(code).path("refresh_token").asText() : null;
        reassignUsername(false);

        mvc.perform(refresh ? refreshRequest(refreshToken) : exchangeRequest(code))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"))
            .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void existingDeviceLoginRetainsOriginalIdentityWhenUsernameChangesBeforeConfirmation() throws Exception {
        String client = PocketHiveDeviceAuth.HIVE_CLIENT_ID;
        JsonNode device = startDevice(client);
        MockHttpSession session = login(originalUsername);
        reassignUsername(true);

        confirmDevice(device, client, session);
        JsonNode token = response(mvc.perform(pollDevice(client, device.path("device_code").asText()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertBrowserIdentity(token);
    }

    @Test
    void existingLoginAuthorizesOriginalIdentityWhenUsernameChangesBeforeCodeRequest() throws Exception {
        MockHttpSession session = login(originalUsername);
        reassignUsername(true);

        assertMcpIdentity(exchange(authorizeMcp(session)));
    }

    @Test
    void replacementUsernameOwnerCannotReuseOriginalUsersConsent() throws Exception {
        authorizeMcp(login(originalUsername));
        reassignUsername(true);

        MvcResult authorization = beginMcp(login(originalUsername));
        URI redirect = URI.create(authorization.getResponse().getRedirectedUrl());
        assertThat(redirect.getPath()).isEqualTo("/oauth/consent");
        assertThat(query(redirect.toString(), "client_id")).isEqualTo(MCP_CLIENT);
    }

    private void reassignUsername(boolean originalActive) throws Exception {
        upsert(originalId, renamedUsername, originalActive);
        grants(originalId, currentGrants);
        UUID replacementId = UUID.randomUUID();
        upsert(replacementId, originalUsername, true);
        grants(replacementId, List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.ALL,
            PocketHiveResourceTypes.DEPLOYMENT, "*")));
    }

    private void upsert(UUID id, String username, boolean active) throws Exception {
        mvc.perform(put("/api/auth/admin/users/{id}", id).header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "displayName", username, "active", active))))
            .andExpect(status().isOk());
    }

    private void grants(UUID id, List<AuthGrantDto> grants) throws Exception {
        mvc.perform(put("/api/auth/admin/users/{id}/grants", id).header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("grants", grants))))
            .andExpect(status().isOk());
    }

    private void assertBrowserIdentity(JsonNode token) throws Exception {
        var identity = mvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token.path("access_token").asText()))
            .andExpect(status().isOk()).andReturn();
        assertIdentity(response(identity.getResponse().getContentAsString()));
    }

    private void assertMcpIdentity(JsonNode token) throws Exception {
        var introspection = mvc.perform(post("/oauth/introspect")
                .with(httpBasic("pockethive-mcp", "pockethive-mcp-local-introspection-secret"))
                .param("token", token.path("access_token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.sub").value(originalId.toString())).andReturn();
        assertIdentity(response(introspection.getResponse().getContentAsString()).path("principal"));
        assertThat(List.of(token.path("scope").asText().split(" ")))
            .containsExactlyInAnyOrder(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);
    }

    private void assertIdentity(JsonNode identity) {
        assertThat(identity.path("id").asText()).isEqualTo(originalId.toString());
        assertThat(identity.path("username").asText()).isEqualTo(renamedUsername);
        assertThat(identity.path("grants")).isEqualTo(mapper.valueToTree(currentGrants));
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/oauth/dev/login").session(session).with(csrf()).param("username", username))
            .andExpect(status().is3xxRedirection());
        return session;
    }

    private JsonNode startDevice(String client) throws Exception {
        return response(mvc.perform(post(PocketHiveDeviceAuth.AUTHORIZATION_PATH).param("client_id", client)
                .param("resource", PocketHiveBrowserAuth.RESOURCE).param("scope", PocketHiveBrowserAuth.SCOPE))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void confirmDevice(JsonNode device, String client, MockHttpSession session) throws Exception {
        var verification = mvc.perform(get(PocketHiveDeviceAuth.VERIFICATION_PATH).session(session)
                .queryParam("user_code", device.path("user_code").asText()))
            .andExpect(status().is3xxRedirection()).andReturn();
        String consentState = query(verification.getResponse().getRedirectedUrl(), "state");
        mvc.perform(post(PocketHiveDeviceAuth.VERIFICATION_PATH).session(session).with(csrf())
                .param("client_id", client).param("state", consentState)
                .param("user_code", device.path("user_code").asText()).param("scope", PocketHiveBrowserAuth.SCOPE))
            .andExpect(status().is3xxRedirection());
    }

    private MockHttpServletRequestBuilder pollDevice(String client, String deviceCode) {
        return post("/oauth/token").param("client_id", client)
            .param("grant_type", AuthorizationGrantType.DEVICE_CODE.getValue())
            .param("device_code", deviceCode).param("resource", PocketHiveBrowserAuth.RESOURCE);
    }

    private String authorizeMcp(MockHttpSession session) throws Exception {
        MvcResult authorization = beginMcp(session);
        String redirect = authorization.getResponse().getRedirectedUrl();
        if ("/oauth/consent".equals(URI.create(redirect).getPath())) {
            redirect = mvc.perform(post("/oauth/authorize").session(session).with(csrf())
                    .param("client_id", MCP_CLIENT).param("state", query(redirect, "state"))
                    .param("scope", PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        }
        assertThat(redirect).startsWith(REDIRECT_URI + "?");
        String code = query(redirect, "code");
        assertThat(code).isNotBlank();
        return code;
    }

    private MvcResult beginMcp(MockHttpSession session) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return mvc.perform(get("/oauth/authorize").session(session)
                .queryParam("response_type", "code").queryParam("client_id", MCP_CLIENT)
                .queryParam("redirect_uri", REDIRECT_URI).queryParam("resource", MCP_RESOURCE)
                .queryParam("scope", MCP_SCOPES).queryParam("state", UUID.randomUUID().toString())
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection()).andReturn();
    }

    private MockHttpServletRequestBuilder exchangeRequest(String code) {
        return post("/oauth/token").param("grant_type", AuthorizationGrantType.AUTHORIZATION_CODE.getValue())
            .param("client_id", MCP_CLIENT).param("code", code).param("redirect_uri", REDIRECT_URI)
            .param("resource", MCP_RESOURCE).param("code_verifier", VERIFIER);
    }

    private JsonNode exchange(String code) throws Exception {
        return response(mvc.perform(exchangeRequest(code)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder refreshRequest(String token) {
        return post("/oauth/token").param("grant_type", AuthorizationGrantType.REFRESH_TOKEN.getValue())
            .param("client_id", MCP_CLIENT).param("refresh_token", token).param("resource", MCP_RESOURCE);
    }

    private JsonNode response(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static String query(String url, String name) {
        return URLDecoder.decode(UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name),
            StandardCharsets.UTF_8);
    }
}
