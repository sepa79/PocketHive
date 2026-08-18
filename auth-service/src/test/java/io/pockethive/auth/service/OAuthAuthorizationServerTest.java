package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class OAuthAuthorizationServerTest {
    private static final String CLIENT_ID = "pockethive-vscode";
    private static final String REDIRECT_URI = "http://127.0.0.1:57548/callback";
    private static final String RESOURCE = "http://localhost:8080/mcp";
    private static final String VERIFIER = "test-verifier-that-is-at-least-forty-three-characters-long";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void publishesCanonicalAuthorizationServerMetadata() throws Exception {
        MvcResult result = mvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://localhost:8080/auth-service"))
            .andExpect(jsonPath("$.authorization_endpoint").value(
                "http://localhost:8080/auth-service/oauth/authorize"))
            .andExpect(jsonPath("$.token_endpoint").value("http://localhost:8080/auth-service/oauth/token"))
            .andExpect(jsonPath("$.introspection_endpoint").value(
                "http://localhost:8080/auth-service/oauth/introspect"))
            .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
            .andReturn();
        JsonNode metadata = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(arrayValues(metadata, "response_types_supported"))
            .containsExactly("code");
        assertThat(arrayValues(metadata, "grant_types_supported"))
            .containsExactly("authorization_code");
        assertThat(arrayValues(metadata, "token_endpoint_auth_methods_supported"))
            .containsExactly("none");
        assertThat(arrayValues(metadata, "introspection_endpoint_auth_methods_supported"))
            .containsExactly("client_secret_basic");
        assertThat(arrayValues(metadata, "scopes_supported"))
            .containsExactlyInAnyOrderElementsOf(PocketHiveMcpScopes.ALL);
        assertThat(metadata.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "issuer", "authorization_endpoint", "token_endpoint",
            "token_endpoint_auth_methods_supported", "scopes_supported",
            "response_types_supported", "grant_types_supported", "introspection_endpoint",
            "introspection_endpoint_auth_methods_supported", "code_challenge_methods_supported");
    }

    @Test
    void browserFlowUsesCanonicalPublicIssuerRoutes() throws Exception {
        mvc.perform(get("/oauth/authorize")
                .header("X-Forwarded-Host", "localhost:8080")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Prefix", "/auth-service")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("resource", RESOURCE)
                .queryParam("scope", PocketHiveMcpScopes.DISCOVER)
                .queryParam("state", "public-route-state")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost:8080/auth-service/oauth/dev/login"));

        mvc.perform(get("/oauth/dev/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "action=\"http://localhost:8080/auth-service/oauth/dev/login\"")));

        mvc.perform(post("/oauth/dev/login")
                .with(csrf())
                .param("username", "local-admin"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void consentFormBindsExplicitFieldsAndPostsToCanonicalIssuer() throws Exception {
        mvc.perform(get("/oauth/consent")
                .param("client_id", CLIENT_ID)
                .param("state", "consent-state")
                .param("scope", PocketHiveMcpScopes.DISCOVER))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "action=\"http://localhost:8080/auth-service/oauth/authorize\"")))
            .andExpect(content().string(containsString(PocketHiveMcpScopes.DISCOVER)));
    }

    private static List<String> arrayValues(JsonNode metadata, String field) {
        return StreamSupport.stream(metadata.path(field).spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    }

    @Test
    @WithMockUser(username = "local-admin")
    void authorizationCodePkceIssuesAudienceBoundOpaqueTokenAndRejectsReplay() throws Exception {
        String state = "client-state-123";
        MvcResult authorization = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("resource", RESOURCE)
                .queryParam("scope", PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ)
                .queryParam("state", state)
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        URI consent = URI.create(authorization.getResponse().getRedirectedUrl());
        assertThat(consent.getPath()).isEqualTo("/oauth/consent");
        String consentState = URLDecoder.decode(UriComponentsBuilder.fromUri(consent).build()
            .getQueryParams().getFirst("state"), StandardCharsets.UTF_8);

        MvcResult approved = mvc.perform(post("/oauth/authorize")
                .with(csrf())
                .param("client_id", CLIENT_ID)
                .param("state", consentState)
                .param("scope", PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        URI callback = URI.create(approved.getResponse().getRedirectedUrl());
        assertThat(callback.getScheme() + "://" + callback.getAuthority() + callback.getPath())
            .isEqualTo(REDIRECT_URI);
        String code = UriComponentsBuilder.fromUri(callback).build().getQueryParams().getFirst("code");
        assertThat(UriComponentsBuilder.fromUri(callback).build().getQueryParams().getFirst("state"))
            .isEqualTo(state);

        MvcResult tokenResult = mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(org.hamcrest.Matchers.startsWith("phmcp_")))
            .andExpect(jsonPath("$.refresh_token").doesNotExist())
            .andReturn();
        JsonNode token = mapper.readTree(tokenResult.getResponse().getContentAsString());

        mvc.perform(post("/oauth/introspect")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .httpBasic("pockethive-mcp", "pockethive-mcp-local-introspection-secret"))
                .param("token", token.path("access_token").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.client_id").value(CLIENT_ID))
            .andExpect(jsonPath("$.sub").value("11111111-1111-1111-1111-111111111111"))
            .andExpect(jsonPath("$.aud[0]").value(RESOURCE))
            .andExpect(jsonPath("$.principal.username").value("local-admin"));

        mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void rejectsMissingOrWrongResourcePlainPkceAndWrongRedirectWithoutFallback() throws Exception {
        invalidAuthorize(null, REDIRECT_URI, "S256");
        invalidAuthorize("http://localhost:8080/other", REDIRECT_URI, "S256");
        invalidAuthorize(RESOURCE, REDIRECT_URI, "plain");
        invalidAuthorize(RESOURCE, "http://127.0.0.1:57549/callback", "S256");
    }

    private void invalidAuthorize(String resource, String redirect, String method) throws Exception {
        var request = get("/oauth/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", CLIENT_ID)
            .queryParam("redirect_uri", redirect)
            .queryParam("scope", PocketHiveMcpScopes.DISCOVER)
            .queryParam("state", "state")
            .queryParam("code_challenge", challenge(VERIFIER))
            .queryParam("code_challenge_method", method);
        if (resource != null) {
            request.queryParam("resource", resource);
        }
        mvc.perform(request).andExpect(status().isBadRequest());
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
