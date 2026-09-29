package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties = {
    "POCKETHIVE_ALLOW_REMOTE_HTTP=true",
    "POCKETHIVE_AUTH_OAUTH_ISSUER=http://lab.example:8088/auth-service",
    "POCKETHIVE_AUTH_OAUTH_RESOURCE=http://lab.example:8088/mcp"
})
@AutoConfigureMockMvc
class RemoteHttpOAuthTest {
    private static final String RESOURCE = "http://lab.example:8088/mcp";
    private static final String REDIRECT = "http://127.0.0.1:38125/callback";
    private static final String CLIENT = "pockethive-vscode";
    private static final String VERIFIER = "remote-http-verifier-that-is-at-least-forty-three-characters-long";
    private static final String[] SCOPES = {"pockethive:mcp:discover", "pockethive:mcp:read"};

    @TempDir
    static Path dynamicClientStateDirectory;

    @DynamicPropertySource
    static void dynamicClientState(DynamicPropertyRegistry registry) {
        registry.add("pockethive.auth-service.oauth.dynamic-client-state-path",
            () -> dynamicClientStateDirectory.resolve("dynamic-clients.json").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    @WithMockUser(username = "local-admin")
    void explicitHttpPublishesMatchingMetadataAndPreservesPkceRefreshAndRevocation() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://lab.example:8088/auth-service"))
            .andExpect(jsonPath("$.token_endpoint").value("http://lab.example:8088/auth-service/oauth/token"));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        var authorization = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code").queryParam("client_id", CLIENT)
                .queryParam("redirect_uri", REDIRECT).queryParam("resource", RESOURCE)
                .queryParam("scope", String.join(" ", SCOPES)).queryParam("state", "remote-state")
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection()).andReturn();
        URI consent = URI.create(authorization.getResponse().getRedirectedUrl());
        assertThat(consent.getPath()).isEqualTo("/oauth/consent");
        String consentState = URLDecoder.decode(UriComponentsBuilder.fromUri(consent).build()
            .getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
        var approved = mvc.perform(post("/oauth/authorize")
                .session((MockHttpSession) authorization.getRequest().getSession(false)).with(csrf())
                .param("client_id", CLIENT).param("state", consentState).param("scope", SCOPES))
            .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(approved.getResponse().getRedirectedUrl());
        assertThat(callback.getScheme() + "://" + callback.getAuthority() + callback.getPath()).isEqualTo(REDIRECT);
        var query = UriComponentsBuilder.fromUri(callback).build().getQueryParams();
        assertThat(query.getFirst("state")).isEqualTo("remote-state");
        String code = query.getFirst("code");
        assertThat(code).isNotBlank();
        var tokenResponse = mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code").param("client_id", CLIENT).param("code", code)
                .param("redirect_uri", REDIRECT).param("resource", RESOURCE).param("code_verifier", VERIFIER))
            .andExpect(status().isOk()).andReturn();
        var token = mapper.readTree(tokenResponse.getResponse().getContentAsString());
        mvc.perform(post("/oauth/token").param("grant_type", "refresh_token").param("client_id", CLIENT)
                .param("refresh_token", token.path("refresh_token").asText()).param("resource", "http://other.example/mcp"))
            .andExpect(status().isBadRequest());
        var renewedResponse = mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token").param("client_id", CLIENT)
                .param("refresh_token", token.path("refresh_token").asText()).param("resource", RESOURCE))
            .andExpect(status().isOk()).andReturn();
        var renewed = mapper.readTree(renewedResponse.getResponse().getContentAsString());
        assertThat(renewed.path("refresh_token").asText()).isNotEqualTo(token.path("refresh_token").asText());
        mvc.perform(post("/oauth/revoke").param("client_id", CLIENT)
                .param("token", renewed.path("refresh_token").asText()).param("token_type_hint", "refresh_token"))
            .andExpect(status().isOk());
        mvc.perform(post("/oauth/token").param("grant_type", "refresh_token").param("client_id", CLIENT)
                .param("refresh_token", renewed.path("refresh_token").asText()).param("resource", RESOURCE))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void allowanceDoesNotPermitMissingPkceOrRemoteCallback() throws Exception {
        mvc.perform(get("/oauth/authorize").queryParam("response_type", "code").queryParam("client_id", CLIENT)
                .queryParam("redirect_uri", REDIRECT).queryParam("resource", RESOURCE)
                .queryParam("scope", String.join(" ", SCOPES)).queryParam("state", "missing-pkce"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/oauth/authorize").queryParam("response_type", "code").queryParam("client_id", CLIENT)
                .queryParam("redirect_uri", "http://other.example/callback").queryParam("resource", RESOURCE)
                .queryParam("scope", String.join(" ", SCOPES)).queryParam("state", "wrong-callback")
                .queryParam("code_challenge", "a".repeat(43)).queryParam("code_challenge_method", "S256"))
            .andExpect(status().isBadRequest());
    }
}
