package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.auth.service.domain.StoredUser;
import io.pockethive.auth.service.service.InMemoryUserStore;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class OAuthAuthorizationServerTest {
    private static final String CLIENT_ID = "pockethive-vscode";
    private static final String REDIRECT_URI = "http://127.0.0.1:38125/callback";
    private static final String RESOURCE = "http://localhost:8080/mcp";
    private static final String VERIFIER = "test-verifier-that-is-at-least-forty-three-characters-long";

    @TempDir
    static Path dynamicClientStateDirectory;

    @DynamicPropertySource
    static void dynamicClientState(DynamicPropertyRegistry registry) {
        registry.add("pockethive.auth-service.oauth.dynamic-client-state-path",
            () -> dynamicClientStateDirectory.resolve("dynamic-clients.json").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired InMemoryUserStore users;

    @Test
    void publishesCanonicalAuthorizationServerMetadata() throws Exception {
        MvcResult result = mvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("http://localhost:8080/auth-service"))
            .andExpect(jsonPath("$.authorization_endpoint").value(
                "http://localhost:8080/auth-service/oauth/authorize"))
            .andExpect(jsonPath("$.token_endpoint").value("http://localhost:8080/auth-service/oauth/token"))
            .andExpect(jsonPath("$.registration_endpoint").value(
                "http://localhost:8080/auth-service/oauth/register"))
            .andExpect(jsonPath("$.introspection_endpoint").value(
                "http://localhost:8080/auth-service/oauth/introspect"))
            .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
            .andReturn();
        JsonNode metadata = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(arrayValues(metadata, "response_types_supported"))
            .containsExactly("code");
        assertThat(arrayValues(metadata, "grant_types_supported"))
            .containsExactly("authorization_code", "refresh_token");
        assertThat(arrayValues(metadata, "token_endpoint_auth_methods_supported"))
            .containsExactly("none");
        assertThat(metadata.path("revocation_endpoint").asText())
            .isEqualTo("http://localhost:8080/auth-service/oauth/revoke");
        assertThat(arrayValues(metadata, "revocation_endpoint_auth_methods_supported"))
            .containsExactly("none");
        assertThat(arrayValues(metadata, "introspection_endpoint_auth_methods_supported"))
            .containsExactly("client_secret_basic");
        assertThat(arrayValues(metadata, "scopes_supported"))
            .containsExactlyElementsOf(PocketHiveMcpScopes.COMPANION_ORDERED);
        assertThat(metadata.fieldNames()).toIterable().containsExactlyInAnyOrder(
            "issuer", "authorization_endpoint", "token_endpoint",
            "registration_endpoint",
            "token_endpoint_auth_methods_supported", "scopes_supported",
            "response_types_supported", "grant_types_supported", "introspection_endpoint",
            "introspection_endpoint_auth_methods_supported", "code_challenge_methods_supported",
            "revocation_endpoint", "revocation_endpoint_auth_methods_supported");

        mvc.perform(post("/oauth/register")
                .contentType("application/json")
                .content(dynamicRegistration("Discovery contract client",
                    "http://127.0.0.1:38122/oauth/callback",
                    String.join(" ", arrayValues(metadata, "scopes_supported")))))
            .andExpect(status().isCreated());
    }

    @Test
    void dynamicallyRegistersAndAuthorizesAStandardsConformingPublicMcpClient() throws Exception {
        String redirectUri = "http://127.0.0.1:38123/oauth/callback";
        MvcResult registration = mvc.perform(post("/oauth/register")
                .contentType("application/json")
                .content(dynamicRegistration("Portable MCP test client", redirectUri,
                    PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.client_id").isNotEmpty())
            .andExpect(jsonPath("$.client_secret").doesNotExist())
            .andExpect(jsonPath("$.client_name").value("Portable MCP test client"))
            .andExpect(jsonPath("$.redirect_uris[0]").value(redirectUri))
            .andExpect(jsonPath("$.grant_types[0]").value("authorization_code"))
            .andExpect(jsonPath("$.grant_types[1]").value("refresh_token"))
            .andExpect(jsonPath("$.response_types[0]").value("code"))
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
            .andReturn();
        String clientId = mapper.readTree(registration.getResponse().getContentAsString())
            .path("client_id").asText();

        mvc.perform(get("/oauth/consent")
                .with(user("local-admin"))
                .param("client_id", clientId)
                .param("state", "portable-consent")
                .param("scope", PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Portable MCP test client")));

        MvcResult authorization = mvc.perform(get("/oauth/authorize")
                .with(user("local-admin"))
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("resource", RESOURCE)
                .queryParam("scope", PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ)
                .queryParam("state", "portable-state")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String code = UriComponentsBuilder.fromUri(authorizationCallbackFor(
                authorization, clientId, redirectUri,
                PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))
            .build().getQueryParams().getFirst("code");

        MvcResult tokenResult = mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(org.hamcrest.Matchers.startsWith("phmcp_")))
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.startsWith("phrfr_")))
            .andExpect(jsonPath("$.scope").isNotEmpty())
            .andDo(result -> assertThat(Set.copyOf(List.of(
                    mapper.readTree(result.getResponse().getContentAsString()).path("scope").asText().split(" "))))
                .containsExactlyInAnyOrder(PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))
            .andReturn();
        String refreshToken = mapper.readTree(tokenResult.getResponse().getContentAsString())
            .path("refresh_token").asText();

        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("refresh_token", refreshToken)
                .param("resource", RESOURCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(org.hamcrest.Matchers.startsWith("phmcp_")))
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.startsWith("phrfr_"),
                org.hamcrest.Matchers.not(refreshToken))));
    }

    @Test
    void dynamicallyRegisteredLocalhostClientMayRotateOnlyItsLoopbackPort() throws Exception {
        String registeredRedirect = "http://localhost:52000/oauth/callback";
        String runtimeRedirect = "http://localhost:62810/oauth/callback";
        MvcResult registration = mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dynamicRegistration("Rotating localhost client", registeredRedirect,
                    PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ)))
            .andExpect(status().isCreated())
            .andReturn();
        String clientId = mapper.readTree(registration.getResponse().getContentAsString())
            .path("client_id").asText();

        MvcResult authorization = mvc.perform(get("/oauth/authorize")
                .with(user("local-admin"))
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", runtimeRedirect)
                .queryParam("resource", RESOURCE)
                .queryParam("scope", PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ)
                .queryParam("state", "rotating-localhost-state")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String code = UriComponentsBuilder.fromUri(authorizationCallbackFor(
                authorization, clientId, runtimeRedirect,
                PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))
            .build().getQueryParams().getFirst("code");

        mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", code)
                .param("redirect_uri", runtimeRedirect)
                .param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(org.hamcrest.Matchers.startsWith("phmcp_")));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void localhostPortRotationAcceptsPortlessRegistrationAndBoundaryPorts() throws Exception {
        MvcResult registration = mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dynamicRegistration("Portless localhost client",
                    "http://localhost/oauth/callback", PocketHiveMcpScopes.DISCOVER)))
            .andExpect(status().isCreated())
            .andReturn();
        String clientId = mapper.readTree(registration.getResponse().getContentAsString())
            .path("client_id").asText();

        for (int runtimePort : List.of(1, 65_535)) {
            mvc.perform(get("/oauth/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", clientId)
                    .queryParam("redirect_uri", "http://localhost:" + runtimePort + "/oauth/callback")
                    .queryParam("resource", RESOURCE)
                    .queryParam("scope", PocketHiveMcpScopes.DISCOVER)
                    .queryParam("state", "boundary-port-" + runtimePort)
                    .queryParam("code_challenge", challenge(VERIFIER))
                    .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection());
        }
    }

    @Test
    @WithMockUser(username = "local-admin")
    void localhostPortRotationDoesNotRelaxAnyOtherRedirectComponent() throws Exception {
        String registeredRedirect = "http://localhost:52000/oauth/callback?channel=amazon-q";
        MvcResult registration = mvc.perform(post("/oauth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dynamicRegistration("Bounded localhost client", registeredRedirect,
                    PocketHiveMcpScopes.DISCOVER)))
            .andExpect(status().isCreated())
            .andReturn();
        String clientId = mapper.readTree(registration.getResponse().getContentAsString())
            .path("client_id").asText();

        for (String invalidRedirect : List.of(
            "https://localhost:62810/oauth/callback?channel=amazon-q",
            "http://127.0.0.1:62810/oauth/callback?channel=amazon-q",
            "http://localhost:62810/other?channel=amazon-q",
            "http://localhost:62810/oauth/callback?channel=other",
            "http://user@localhost:62810/oauth/callback?channel=amazon-q",
            "http://localhost:62810/oauth/callback?channel=amazon-q#fragment",
            "http://localhost/oauth/callback?channel=amazon-q",
            "http://localhost:0/oauth/callback?channel=amazon-q",
            "http://localhost:65536/oauth/callback?channel=amazon-q")) {
            mvc.perform(get("/oauth/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", clientId)
                    .queryParam("redirect_uri", invalidRedirect)
                    .queryParam("resource", RESOURCE)
                    .queryParam("scope", PocketHiveMcpScopes.DISCOVER)
                    .queryParam("state", "bounded-localhost-state")
                    .queryParam("code_challenge", challenge(VERIFIER))
                    .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void dynamicallyRegistersAmazonQMetadataThatOmitsPublicMethodAndScopeSelection() throws Exception {
        String redirectUri = "http://localhost:38124/oauth/callback";

        mvc.perform(post("/oauth/register")
                .contentType("application/json")
                .content(dynamicRegistrationWithoutScope("kiro", redirectUri)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.client_id").isNotEmpty())
            .andExpect(jsonPath("$.client_secret").doesNotExist())
            .andExpect(jsonPath("$.client_name").value("kiro"))
            .andExpect(jsonPath("$.redirect_uris[0]").value(redirectUri))
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
            .andExpect(jsonPath("$.scope").value(String.join(" ", PocketHiveMcpScopes.COMPANION_ORDERED)));
    }

    @Test
    void dynamicRegistrationRejectsUnsafeOrOverPrivilegedClientMetadata() throws Exception {
        String safeRedirect = "http://localhost:38123/callback";
        mvc.perform(post("/oauth/register")
                .contentType("application/json")
                .content(dynamicRegistration("Unsafe", "http://remote.example/callback",
                    PocketHiveMcpScopes.DISCOVER)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
        mvc.perform(post("/oauth/register")
                .contentType("application/json")
                .content(dynamicRegistration("Over privileged", safeRedirect,
                    PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.CLEANUP)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
        mvc.perform(post("/oauth/register")
                .contentType("application/json")
                .content(dynamicRegistration("Confidential", safeRedirect,
                    PocketHiveMcpScopes.DISCOVER).replace(
                        "\"token_endpoint_auth_method\":\"none\"",
                        "\"token_endpoint_auth_method\":\"client_secret_basic\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_client_metadata"));
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
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().encoding(StandardCharsets.UTF_8))
            .andExpect(content().string(containsString("class=\"auth-shell\"")))
            .andExpect(content().string(containsString("class=\"auth-brand__logo\"")))
            .andExpect(content().string(containsString("pockethive-auth.css")))
            .andExpect(content().string(containsString("autocomplete=\"username\"")))
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
    void unknownAuthorizationClientRendersBoundedPocketHiveFailure() throws Exception {
        String untrustedClientId = "unknown-<script>alert(1)</script>";
        String untrustedState = "state-<img src=x onerror=alert(1)>";

        mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", untrustedClientId)
                .queryParam("redirect_uri", "http://localhost:52000/oauth/callback")
                .queryParam("resource", RESOURCE)
                .queryParam("scope", PocketHiveMcpScopes.DISCOVER)
                .queryParam("state", untrustedState)
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(containsString("class=\"auth-shell\"")))
            .andExpect(content().string(containsString("PocketHive")))
            .andExpect(content().string(containsString("Authorization could not continue")))
            .andExpect(content().string(containsString("invalid_request")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Whitelabel"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString(untrustedClientId))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString(untrustedState))));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void unknownConsentClientRendersBoundedPocketHiveFailure() throws Exception {
        mvc.perform(get("/oauth/consent")
                .accept(MediaType.TEXT_HTML)
                .param("client_id", "unknown-client")
                .param("state", "untrusted-state")
                .param("scope", PocketHiveMcpScopes.DISCOVER))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("Authorization could not continue")))
            .andExpect(content().string(containsString("invalid_request")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Whitelabel"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("unknown-client"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("untrusted-state"))));
    }

    @Test
    void unauthenticatedConsentRequestUsesTheCanonicalLoginEntryPoint() throws Exception {
        mvc.perform(get("/oauth/consent")
                .accept(MediaType.TEXT_HTML)
                .header("X-Forwarded-Host", "localhost:8080")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Prefix", "/auth-service")
                .param("client_id", CLIENT_ID)
                .param("state", "consent-state")
                .param("scope", PocketHiveMcpScopes.DISCOVER))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost:8080/auth-service/oauth/dev/login"));
    }

    @Test
    void unknownDevUserRendersBoundedPocketHiveFailure() throws Exception {
        mvc.perform(post("/oauth/dev/login")
                .with(csrf())
                .accept(MediaType.TEXT_HTML)
                .param("username", "unknown-user"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("Authorization could not continue")))
            .andExpect(content().string(containsString("access_denied")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Whitelabel"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("unknown-user"))));
    }

    @Test
    void rejectedDevLoginCsrfRendersBoundedPocketHiveFailure() throws Exception {
        mvc.perform(post("/oauth/dev/login")
                .accept(MediaType.TEXT_HTML)
                .param("username", "local-admin"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("Authorization could not continue")))
            .andExpect(content().string(containsString("access_denied")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Whitelabel"))));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void consentFormBindsExplicitFieldsAndPostsToCanonicalIssuer() throws Exception {
        mvc.perform(get("/oauth/consent")
                .param("client_id", CLIENT_ID)
                .param("state", "consent-state")
                .param("scope", PocketHiveMcpScopes.ALL_ORDERED.toArray(String[]::new)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().encoding(StandardCharsets.UTF_8))
            .andExpect(content().string(containsString("class=\"auth-shell\"")))
            .andExpect(content().string(containsString("<fieldset")))
            .andExpect(content().string(containsString("Requested permissions")))
            .andExpect(content().string(containsString(
                "Discover PocketHive capabilities and connected skills")))
            .andExpect(content().string(containsString("Read PocketHive runtime and scenario data")))
            .andExpect(content().string(containsString(
                "Start, stop, and operate explicit PocketHive targets")))
            .andExpect(content().string(containsString("Prepare and validate Scenario Bundles")))
            .andExpect(content().string(containsString(
                "Publish an explicitly validated Scenario Bundle")))
            .andExpect(content().string(containsString(
                "Execute an approved runtime cleanup plan")))
            .andExpect(content().string(containsString("You are authorizing the selected PocketHive environment")))
            .andExpect(content().string(containsString(
                "action=\"http://localhost:8080/auth-service/oauth/authorize\"")))
            .andExpect(content().string(containsString(PocketHiveMcpScopes.DISCOVER)));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void consentFormOmitsBlankScopeSegments() throws Exception {
        mvc.perform(get("/oauth/consent")
                .param("client_id", CLIENT_ID)
                .param("state", "consent-state")
                .param("scope", "", PocketHiveMcpScopes.DISCOVER))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "name=\"scope\" value=\"" + PocketHiveMcpScopes.DISCOVER + "\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString(
                "name=\"scope\" value=\"\""))));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void consentFormEscapesUntrustedBrowserValues() throws Exception {
        MvcResult registration = mvc.perform(post("/oauth/register")
                .contentType("application/json")
                .content(dynamicRegistration("<script>client</script>",
                    "http://127.0.0.1:38124/callback", PocketHiveMcpScopes.DISCOVER)))
            .andExpect(status().isCreated())
            .andReturn();
        String clientId = mapper.readTree(registration.getResponse().getContentAsString())
            .path("client_id").asText();
        mvc.perform(get("/oauth/consent")
                .param("client_id", clientId)
                .param("state", "state\" autofocus onfocus=\"alert(1)")
                .param("scope", "<img/src=x/onerror=alert(1)>"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("&lt;script&gt;client&lt;/script&gt;")))
            .andExpect(content().string(containsString("state&quot; autofocus onfocus=&quot;alert(1)")))
            .andExpect(content().string(containsString("&lt;img/src=x/onerror=alert(1)&gt;")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<script>client</script>"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<img/src=x/onerror"))));
    }

    @Test
    void publishesPocketHiveAuthorizationStylesWithoutAuthentication() throws Exception {
        mvc.perform(get("/oauth/pockethive-auth.css"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/css"))
            .andExpect(content().string(containsString("--ph-brand-hive: #ffc107")))
            .andExpect(content().string(containsString("prefers-reduced-motion")));
        mvc.perform(get("/oauth/logo.svg"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
            .andExpect(content().string(containsString("PocketHive")));
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
        URI callback = authorizationCallback(authorization,
            PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ);
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
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.startsWith("phrfr_")))
            .andReturn();
        JsonNode token = mapper.readTree(tokenResult.getResponse().getContentAsString());

        MvcResult refreshedResult = mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("refresh_token", token.path("refresh_token").asText())
                .param("resource", RESOURCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(org.hamcrest.Matchers.startsWith("phmcp_")))
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.startsWith("phrfr_")))
            .andReturn();
        JsonNode refreshed = mapper.readTree(refreshedResult.getResponse().getContentAsString());
        assertThat(refreshed.path("access_token").asText()).isNotEqualTo(token.path("access_token").asText());
        assertThat(refreshed.path("refresh_token").asText()).isNotEqualTo(token.path("refresh_token").asText());

        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("refresh_token", token.path("refresh_token").asText())
                .param("resource", RESOURCE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));

        mvc.perform(post("/oauth/introspect")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .httpBasic("pockethive-mcp", "pockethive-mcp-local-introspection-secret"))
                .param("token", refreshed.path("access_token").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.client_id").value(CLIENT_ID))
            .andExpect(jsonPath("$.sub").value("11111111-1111-1111-1111-111111111111"))
            .andExpect(jsonPath("$.aud[0]").value(RESOURCE))
            .andExpect(jsonPath("$.principal.username").value("local-admin"));

        mvc.perform(post("/oauth/revoke")
                .param("client_id", CLIENT_ID)
                .param("token", refreshed.path("access_token").asText())
                .param("token_type_hint", "access_token"))
            .andExpect(status().isOk());
        mvc.perform(post("/oauth/introspect")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .httpBasic("pockethive-mcp", "pockethive-mcp-local-introspection-secret"))
                .param("token", refreshed.path("access_token").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));

        mvc.perform(post("/oauth/revoke")
                .param("client_id", CLIENT_ID)
                .param("token", refreshed.path("refresh_token").asText())
                .param("token_type_hint", "refresh_token"))
            .andExpect(status().isOk());
        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("refresh_token", refreshed.path("refresh_token").asText())
                .param("resource", RESOURCE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_grant"));

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
    void companionAuthorizationNarrowsOnceToCurrentGrantsAndRefreshNeverWidens() throws Exception {
        assertCompanionSession("local-viewer", List.of(
            PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ));
        assertCompanionSession("local-runner", List.of(
            PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ,
            PocketHiveMcpScopes.OPERATE, PocketHiveMcpScopes.AUTHOR));
        assertCompanionSession("local-admin", PocketHiveMcpScopes.COMPANION_ORDERED);
    }

    @Test
    void companionRefreshInvalidatesImmediatelyAfterCurrentGrantReduction() throws Exception {
        StoredUser runner = users.findByUsername("local-runner").orElseThrow();
        List<AuthGrantDto> originalGrants = runner.grants();
        try {
            List<String> runnerScopes = List.of(
                PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ,
                PocketHiveMcpScopes.OPERATE, PocketHiveMcpScopes.AUTHOR);
            MvcResult authorization = mvc.perform(get("/oauth/authorize")
                    .with(user("local-runner"))
                    .queryParam("response_type", "code")
                    .queryParam("client_id", CLIENT_ID)
                    .queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("resource", RESOURCE)
                    .queryParam("scope", String.join(" ", PocketHiveMcpScopes.COMPANION_ORDERED))
                    .queryParam("state", "grant-reduction")
                    .queryParam("code_challenge", challenge(VERIFIER))
                    .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
            String code = UriComponentsBuilder.fromUri(authorizationCallback(
                    authorization, runnerScopes.toArray(String[]::new)))
                .build().getQueryParams().getFirst("code");
            MvcResult tokenResult = mvc.perform(post("/oauth/token")
                    .param("grant_type", "authorization_code")
                    .param("client_id", CLIENT_ID)
                    .param("code", code)
                    .param("redirect_uri", REDIRECT_URI)
                    .param("resource", RESOURCE)
                    .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
            JsonNode token = mapper.readTree(tokenResult.getResponse().getContentAsString());

            users.replaceGrants(runner.id(), List.of(originalGrants.getFirst()));
            mvc.perform(post("/oauth/token")
                    .param("grant_type", "refresh_token")
                    .param("client_id", CLIENT_ID)
                    .param("refresh_token", token.path("refresh_token").asText())
                    .param("resource", RESOURCE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        } finally {
            users.replaceGrants(runner.id(), originalGrants);
        }
    }

    @Test
    @WithMockUser(username = "local-admin")
    void declaredInteractiveSubsetReceivesARotatingSession() throws Exception {
        MvcResult authorization = mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("resource", RESOURCE)
                .queryParam("scope", PocketHiveMcpScopes.DISCOVER + " " + PocketHiveMcpScopes.READ
                    + " " + PocketHiveMcpScopes.OPERATE)
                .queryParam("state", "privileged-state")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String code = UriComponentsBuilder.fromUri(authorizationCallback(authorization,
                PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ, PocketHiveMcpScopes.OPERATE))
            .build().getQueryParams().getFirst("code");

        mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").value(org.hamcrest.Matchers.startsWith("phmcp_")))
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.startsWith("phrfr_")));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void refreshAndRevocationFailClosedForMalformedPublicClientRequests() throws Exception {
        JsonNode token = issueBaseSession("public-client-boundary-state");
        String refreshToken = token.path("refresh_token").asText();

        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .param("resource", RESOURCE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID, CLIENT_ID)
                .param("refresh_token", refreshToken)
                .param("resource", RESOURCE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("client_secret", "must-not-be-accepted")
                .param("refresh_token", refreshToken)
                .param("resource", RESOURCE))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_client"));
        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", "unknown-public-client")
                .param("refresh_token", refreshToken)
                .param("resource", RESOURCE))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_client"));
        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("refresh_token", refreshToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("refresh_token", refreshToken)
                .param("resource", "http://localhost:8080/not-mcp"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));

        mvc.perform(post("/oauth/revoke")
                .param("token", refreshToken)
                .param("token_type_hint", "refresh_token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/oauth/revoke")
                .param("client_id", CLIENT_ID, CLIENT_ID)
                .param("token", refreshToken)
                .param("token_type_hint", "refresh_token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/oauth/revoke")
                .param("client_id", CLIENT_ID)
                .param("client_secret", "must-not-be-accepted")
                .param("token", refreshToken)
                .param("token_type_hint", "refresh_token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_client"));

        mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("refresh_token", refreshToken)
                .param("resource", RESOURCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.startsWith("phrfr_")));
    }

    @Test
    @WithMockUser(username = "local-admin")
    void rejectsMissingOrWrongResourcePlainPkceAndWrongRedirectWithoutFallback() throws Exception {
        invalidAuthorize(null, REDIRECT_URI, "S256");
        invalidAuthorize("http://localhost:8080/other", REDIRECT_URI, "S256");
        invalidAuthorize(RESOURCE, REDIRECT_URI, "plain");
        invalidAuthorize(RESOURCE, "http://localhost:38125/callback", "S256");
        invalidAuthorize(RESOURCE, "http://127.0.0.1:38125/other", "S256");
        invalidAuthorize(RESOURCE, "http://127.0.0.1:38125/callback?unexpected=true", "S256");
        mvc.perform(get("/oauth/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID, CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("resource", RESOURCE)
                .queryParam("scope", PocketHiveMcpScopes.DISCOVER)
                .queryParam("state", "state")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().isBadRequest());
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

    private URI authorizationCallback(MvcResult authorization, String... scopes) throws Exception {
        return authorizationCallbackFor(authorization, CLIENT_ID, REDIRECT_URI, scopes);
    }

    private URI authorizationCallbackFor(MvcResult authorization, String clientId, String redirectUri,
                                         String... scopes) throws Exception {
        URI redirect = URI.create(authorization.getResponse().getRedirectedUrl());
        URI callback;
        if ("/oauth/consent".equals(redirect.getPath())) {
            String consentState = URLDecoder.decode(UriComponentsBuilder.fromUri(redirect).build()
                .getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
            MockHttpSession session = (MockHttpSession) authorization.getRequest().getSession(false);
            MvcResult approved = mvc.perform(post("/oauth/authorize")
                    .session(session)
                    .with(csrf())
                    .param("client_id", clientId)
                    .param("state", consentState)
                    .param("scope", scopes))
                .andExpect(status().is3xxRedirection())
                .andReturn();
            callback = URI.create(approved.getResponse().getRedirectedUrl());
        } else {
            callback = redirect;
        }
        assertThat(callback.getScheme() + "://" + callback.getAuthority() + callback.getPath())
            .isEqualTo(redirectUri);
        return callback;
    }

    private static String dynamicRegistration(String clientName, String redirectUri, String scopes) {
        return """
            {
              "client_name":"%s",
              "redirect_uris":["%s"],
              "grant_types":["authorization_code","refresh_token"],
              "response_types":["code"],
              "token_endpoint_auth_method":"none",
              "scope":"%s"
            }
            """.formatted(clientName, redirectUri, scopes);
    }

    private static String dynamicRegistrationWithoutScope(String clientName, String redirectUri) {
        return """
            {
              "client_name":"%s",
              "redirect_uris":["%s"],
              "grant_types":["authorization_code","refresh_token"],
              "response_types":["code"]
            }
            """.formatted(clientName, redirectUri);
    }

    private JsonNode issueBaseSession(String state) throws Exception {
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
        String code = UriComponentsBuilder.fromUri(authorizationCallback(authorization,
                PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ))
            .build().getQueryParams().getFirst("code");
        MvcResult token = mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isOk())
            .andReturn();
        return mapper.readTree(token.getResponse().getContentAsString());
    }

    private void assertCompanionSession(String username, List<String> grantedScopes) throws Exception {
        MvcResult authorization = mvc.perform(get("/oauth/authorize")
                .with(user(username))
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("resource", RESOURCE)
                .queryParam("scope", String.join(" ", PocketHiveMcpScopes.COMPANION_ORDERED))
                .queryParam("state", "companion-" + username)
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String code = UriComponentsBuilder.fromUri(authorizationCallback(
                authorization, grantedScopes.toArray(String[]::new)))
            .build().getQueryParams().getFirst("code");
        MvcResult tokenResult = mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.startsWith("phrfr_")))
            .andReturn();
        JsonNode token = mapper.readTree(tokenResult.getResponse().getContentAsString());
        assertThat(Set.copyOf(List.of(token.path("scope").asText().split(" "))))
            .containsExactlyInAnyOrderElementsOf(grantedScopes);

        MvcResult refreshedResult = mvc.perform(post("/oauth/token")
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("refresh_token", token.path("refresh_token").asText())
                .param("resource", RESOURCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refresh_token").value(org.hamcrest.Matchers.startsWith("phrfr_")))
            .andReturn();
        JsonNode refreshed = mapper.readTree(refreshedResult.getResponse().getContentAsString());
        assertThat(Set.copyOf(List.of(refreshed.path("scope").asText().split(" "))))
            .containsExactlyInAnyOrderElementsOf(grantedScopes);
        assertThat(refreshed.path("refresh_token").asText())
            .isNotEqualTo(token.path("refresh_token").asText());
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
