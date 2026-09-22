package io.pockethive.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class OAuthConsentFlowTest {
    private static final String REDIRECT = "http://127.0.0.1:38125/consent-callback";
    private static final String RESOURCE = "http://localhost:8080/mcp";
    private static final String STATE = "original client state +/&%";
    private static final String VERIFIER = "consent-test-verifier-with-at-least-forty-three-characters";
    private static final String[] SCOPES = {PocketHiveMcpScopes.DISCOVER, PocketHiveMcpScopes.READ};

    @TempDir static Path dynamicClientStateDirectory;

    @DynamicPropertySource
    static void dynamicClientState(DynamicPropertyRegistry registry) {
        registry.add("pockethive.auth-service.oauth.dynamic-client-state-path",
            () -> dynamicClientStateDirectory.resolve("clients.json").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void renderedDeclineWithCheckedScopesDeniesWithoutSavingConsent() throws Exception {
        String client = registerClient();
        PendingConsent pending = begin(client, SCOPES);
        String page = mvc.perform(get("/oauth/consent").with(user("local-admin"))
                .param("client_id", client).param("state", pending.handle()).param("scope", SCOPES))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("name=\"consent_action\" value=\"cancel\"",
            "name=\"scope\" value=\"" + PocketHiveMcpScopes.DISCOVER + "\" checked");

        assertDenied(mvc.perform(submit(pending).param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().is3xxRedirection()).andReturn());

        // A new authorization still needs consent: Decline did not save checked grants.
        begin(client, SCOPES);
    }

    @Test
    void emptyScopeSubmissionPreservesSpringsValidatedDenialCallback() throws Exception {
        PendingConsent pending = begin(registerClient(), SCOPES);
        assertDenied(mvc.perform(submit(pending))
            .andExpect(status().is3xxRedirection()).andReturn());
    }

    @Test
    void declineRevokesPriorConsentInsteadOfReusingEarlierGrants() throws Exception {
        String client = registerClient();
        PendingConsent first = begin(client, PocketHiveMcpScopes.DISCOVER);
        assertApproved(mvc.perform(submit(first).param("consent_action", "approve")
                .param("scope", PocketHiveMcpScopes.DISCOVER))
            .andExpect(status().is3xxRedirection()).andReturn());

        PendingConsent additional = begin(client, SCOPES);
        assertDenied(mvc.perform(submit(additional).param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().is3xxRedirection()).andReturn());

        // Spring's denial path revokes the old consent too; even its previous scope prompts again.
        begin(client, PocketHiveMcpScopes.DISCOVER);
    }

    @Test
    void approvalIssuesAUsableCodeAndConsumesTheConsentHandle() throws Exception {
        PendingConsent pending = begin(registerClient(), SCOPES);
        String code = assertApproved(mvc.perform(submit(pending).param("consent_action", "approve")
                .param("scope", SCOPES))
            .andExpect(status().is3xxRedirection()).andReturn());
        MvcResult token = mvc.perform(post("/oauth/token")
                .param("grant_type", "authorization_code").param("client_id", pending.client())
                .param("code", code).param("redirect_uri", REDIRECT).param("resource", RESOURCE)
                .param("code_verifier", VERIFIER))
            .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(token.getResponse().getContentAsString()).path("scope").asText().split(" "))
            .containsExactlyInAnyOrder(SCOPES);
        assertLocalFailure(mvc.perform(submit(pending).param("consent_action", "approve").param("scope", SCOPES))
            .andExpect(status().isBadRequest()).andReturn(), pending.handle());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "cancel"})
    void deniedConsentHandleCannotBeReplayedForEitherDecision(String replayAction) throws Exception {
        PendingConsent pending = begin(registerClient(), SCOPES);
        assertDenied(mvc.perform(submit(pending).param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().is3xxRedirection()).andReturn());
        assertLocalFailure(mvc.perform(submit(pending).param("consent_action", replayAction).param("scope", SCOPES))
            .andExpect(status().isBadRequest()).andReturn(), pending.handle());
    }

    @Test
    void unknownAndRepeatedActionsFailWithoutGrantingOrConsumingConsent() throws Exception {
        PendingConsent pending = begin(registerClient(), SCOPES);
        for (String[] actions : List.of(new String[]{"unexpected-action"}, new String[]{"cancel", "approve"})) {
            assertLocalFailure(mvc.perform(submit(pending).param("consent_action", actions).param("scope", SCOPES))
                .andExpect(status().isBadRequest()).andReturn(), pending.handle());
        }
        assertDenied(mvc.perform(submit(pending).param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().is3xxRedirection()).andReturn());
    }

    @Test
    void declineWithUnknownStateDoesNotTrustRequestParametersForACallback() throws Exception {
        PendingConsent pending = begin(registerClient(), SCOPES);
        String unknownState = "unbound-state-sensitive-marker";
        assertLocalFailure(mvc.perform(submit(pending, pending.client(), unknownState)
                .param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().isBadRequest()).andReturn(), unknownState);
    }

    @Test
    void declineRejectsAClientOrPrincipalThatDoesNotOwnThePendingRequest() throws Exception {
        PendingConsent pending = begin(registerClient(), SCOPES);
        String differentClient = registerClient();
        assertLocalFailure(mvc.perform(submit(pending, differentClient, pending.handle())
                .param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().isBadRequest()).andReturn(), pending.handle(), differentClient);
        assertLocalFailure(mvc.perform(submit(pending).with(user("another-principal"))
                .param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().isBadRequest()).andReturn(), pending.handle());
        assertDenied(mvc.perform(submit(pending).param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().is3xxRedirection()).andReturn());
    }

    @Test
    void unsafeRedirectsStayOnTheBoundedPageForAuthorizationAndConsent() throws Exception {
        String unsafe = "https://untrusted.example/callback?sensitive=redirect-marker";
        PendingConsent pending = begin(registerClient(), SCOPES);
        assertLocalFailure(mvc.perform(authorizeAt(pending.client(), unsafe, SCOPES))
            .andExpect(status().isBadRequest()).andReturn(), unsafe, STATE);
        assertLocalFailure(mvc.perform(submit(pending).param("redirect_uri", unsafe)
                .param("consent_action", "cancel").param("scope", SCOPES))
            .andExpect(status().isBadRequest()).andReturn(), unsafe, pending.handle());
    }

    private String registerClient() throws Exception {
        var body = Map.of("client_name", "Consent behavior test", "redirect_uris", List.of(REDIRECT),
            "grant_types", List.of("authorization_code"), "response_types", List.of("code"),
            "token_endpoint_auth_method", "none", "scope", String.join(" ", SCOPES));
        MvcResult result = mvc.perform(post("/oauth/register").contentType("application/json")
                .content(mapper.writeValueAsBytes(body)))
            .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).required("client_id").asText();
    }

    private PendingConsent begin(String client, String... scopes) throws Exception {
        MvcResult result = mvc.perform(authorize(client, scopes))
            .andExpect(status().is3xxRedirection()).andReturn();
        URI consent = URI.create(result.getResponse().getRedirectedUrl());
        assertThat(consent.getPath()).isEqualTo("/oauth/consent");
        String handle = decoded(query(consent).getFirst("state"));
        assertThat(handle).isNotBlank().isNotEqualTo(STATE);
        return new PendingConsent(client, handle, (MockHttpSession) result.getRequest().getSession(false));
    }

    private MockHttpServletRequestBuilder authorize(String client, String... scopes) throws Exception {
        return authorizeAt(client, REDIRECT, scopes);
    }

    private MockHttpServletRequestBuilder authorizeAt(String client, String redirect, String... scopes) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth/authorize").with(user("local-admin"))
            .queryParam("response_type", "code").queryParam("client_id", client)
            .queryParam("redirect_uri", redirect).queryParam("resource", RESOURCE)
            .queryParam("scope", String.join(" ", scopes)).queryParam("state", STATE)
            .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
    }

    private MockHttpServletRequestBuilder submit(PendingConsent pending) {
        return submit(pending, pending.client(), pending.handle());
    }

    private MockHttpServletRequestBuilder submit(PendingConsent pending, String client, String state) {
        return post("/oauth/authorize").with(user("local-admin")).with(csrf()).session(pending.session())
            .param("client_id", client).param("state", state);
    }

    private void assertDenied(MvcResult result) {
        var callback = callback(result);
        assertThat(callback.keySet()).containsExactlyInAnyOrder("error", "state");
        assertThat(callback.getFirst("error")).isEqualTo("access_denied");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    private String assertApproved(MvcResult result) {
        var callback = callback(result);
        assertThat(callback.keySet()).containsExactlyInAnyOrder("code", "state");
        assertThat(callback.getFirst("code")).isNotBlank();
        return decoded(callback.getFirst("code"));
    }

    private MultiValueMap<String, String> callback(MvcResult result) {
        URI callback = URI.create(result.getResponse().getRedirectedUrl());
        assertThat(callback.getScheme() + "://" + callback.getAuthority() + callback.getPath()).isEqualTo(REDIRECT);
        var parameters = query(callback);
        assertThat(decoded(parameters.getFirst("state"))).isEqualTo(STATE);
        return parameters;
    }

    private void assertLocalFailure(MvcResult result, String... sensitive) throws Exception {
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getResponse().getContentAsString())
            .contains("PocketHive", "Authorization could not continue", "invalid_request")
            .doesNotContain("Whitelabel").doesNotContain(sensitive);
    }

    private static MultiValueMap<String, String> query(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams();
    }

    private static String decoded(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record PendingConsent(String client, String handle, MockHttpSession session) { }
}
