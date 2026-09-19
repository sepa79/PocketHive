package io.pockethive.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;

class McpProtocolSecurityFilterTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private final McpProtocolSecurityFilter filter = filter(Clock.fixed(NOW, ZoneOffset.UTC), 10);

    @Test
    void leavesProtocolRevisionNegotiationToTheTransportSdk() throws Exception {
        for (String revision : new String[] {null, "2026-07-28"}) {
            MockHttpServletRequest request = request("qa-lead", revision, null);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request, response, counting(calls));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void validatesConfigurationAndPassesUnauthenticatedRequestsToOAuthSecurity() throws Exception {
        for (Runnable invalid : List.<Runnable>of(
            () -> new McpProtocolSecurityFilter(null, Duration.ofMinutes(1), 1),
            () -> new McpProtocolSecurityFilter(Clock.systemUTC(), null, 1),
            () -> new McpProtocolSecurityFilter(Clock.systemUTC(), Duration.ZERO, 1),
            () -> new McpProtocolSecurityFilter(Clock.systemUTC(), Duration.ofSeconds(-1), 1),
            () -> new McpProtocolSecurityFilter(Clock.systemUTC(), Duration.ofMinutes(1), 0))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MCP_TRANSPORT_SESSION_CONFIGURATION_INVALID");
        }

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        AtomicInteger calls = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), counting(calls));
        assertThat(calls).hasValue(1);
    }

    @Test
    void treatsABlankSessionHeaderAsInitializationAndReleasesFailedCapacity() throws Exception {
        McpProtocolSecurityFilter bounded = filter(Clock.fixed(NOW, ZoneOffset.UTC), 1);
        MockHttpServletRequest blank = request("qa-lead", "2025-11-25", null);
        blank.addHeader(HttpHeaders.MCP_SESSION_ID, "   ");
        MockHttpServletResponse failed = new MockHttpServletResponse();
        bounded.doFilter(blank, failed, (request, response) -> {
            ((MockHttpServletResponse) response).setStatus(500);
            ((MockHttpServletResponse) response).setHeader(HttpHeaders.MCP_SESSION_ID, "failed-session");
        });

        createSession(bounded, "qa-lead", "test-client", "replacement-session");
    }

    @Test
    void acceptsBearerAuthenticationFromTheSecurityContextAndRequiresAClientId() throws Exception {
        MockHttpServletRequest contextRequest = request("qa-lead", "2025-11-25", null);
        SecurityContextHolder.getContext().setAuthentication(
            (BearerTokenAuthentication) contextRequest.getUserPrincipal());
        contextRequest.setUserPrincipal(null);
        AtomicInteger calls = new AtomicInteger();
        try {
            filter.doFilter(contextRequest, new MockHttpServletResponse(), counting(calls));
            assertThat(calls).hasValue(1);
        } finally {
            SecurityContextHolder.clearContext();
        }

        MockHttpServletRequest missingClient = request("qa-lead", "2025-11-25", null);
        Map<String, Object> attributes = Map.of(
            "iss", "https://issuer.example", "sub", "qa-lead", "client_id", " ");
        missingClient.setUserPrincipal(authentication("qa-lead", attributes));
        assertThatThrownBy(() -> filter.doFilter(
            missingClient, new MockHttpServletResponse(), counting(new AtomicInteger())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MCP_AUTHENTICATED_CLIENT_REQUIRED");
    }

    @Test
    void bindsAProducedTransportSessionToTheVerifiedPrincipalAndRejectsHijack() throws Exception {
        MockHttpServletRequest initialise = request("qa-lead", "2025-11-25", null);
        MockHttpServletResponse initialised = new MockHttpServletResponse();
        filter.doFilter(initialise, initialised, (request, response) -> {
            ((MockHttpServletResponse) response).setStatus(200);
            ((MockHttpServletResponse) response).setHeader(HttpHeaders.MCP_SESSION_ID, "mcp-session-1");
        });

        AtomicInteger ownerCalls = new AtomicInteger();
        MockHttpServletResponse ownerResponse = new MockHttpServletResponse();
        filter.doFilter(request("qa-lead", "2025-11-25", "mcp-session-1"), ownerResponse,
            counting(ownerCalls));
        assertThat(ownerCalls).hasValue(1);

        AtomicInteger attackerCalls = new AtomicInteger();
        MockHttpServletResponse attackerResponse = new MockHttpServletResponse();
        filter.doFilter(request("other", "2025-11-25", "mcp-session-1"), attackerResponse,
            counting(attackerCalls));
        assertThat(attackerResponse.getStatus()).isEqualTo(403);
        assertThat(attackerResponse.getContentType()).isEqualTo("application/json");
        assertThat(attackerResponse.getContentAsString()).contains("MCP_TRANSPORT_SESSION_PRINCIPAL_MISMATCH");
        assertThat(attackerCalls).hasValue(0);

        AtomicInteger otherClientCalls = new AtomicInteger();
        MockHttpServletResponse otherClientResponse = new MockHttpServletResponse();
        filter.doFilter(request("qa-lead", "other-client", "2025-11-25", "mcp-session-1", "POST"),
            otherClientResponse, counting(otherClientCalls));
        assertThat(otherClientResponse.getStatus()).isEqualTo(403);
        assertThat(otherClientCalls).hasValue(0);
    }

    @Test
    void boundsSessionsFailsClosedAtExpiryAndImmediatelyReleasesCapacity() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        McpProtocolSecurityFilter bounded = filter(clock, 1);
        createSession(bounded, "qa-lead", "client-a", "expired-session");
        clock.current = NOW.plusSeconds(61);

        MockHttpServletResponse expired = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", "expired-session", "POST"),
            expired, counting(new AtomicInteger()));
        assertThat(expired.getStatus()).isEqualTo(404);
        assertThat(expired.getContentAsString()).contains("MCP_TRANSPORT_SESSION_EXPIRED");

        MockHttpServletResponse replacement = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", null, "POST"), replacement,
            (request, response) -> {
                ((MockHttpServletResponse) response).setStatus(200);
                ((MockHttpServletResponse) response).setHeader(HttpHeaders.MCP_SESSION_ID, "replacement-session");
            });
        assertThat(replacement.getStatus()).isEqualTo(200);

        MockHttpServletResponse missing = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", "expired-session", "DELETE"),
            missing, counting(new AtomicInteger()));
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(missing.getContentAsString()).contains("MCP_TRANSPORT_SESSION_NOT_FOUND");
    }

    @Test
    void enforcesTransportCapacityUntilAnAuthenticatedDeleteForLiveSessions() throws Exception {
        McpProtocolSecurityFilter bounded = filter(Clock.fixed(NOW, ZoneOffset.UTC), 1);
        createSession(bounded, "qa-lead", "client-a", "live-session");

        MockHttpServletResponse full = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", null, "POST"), full,
            counting(new AtomicInteger()));
        assertThat(full.getStatus()).isEqualTo(429);
        assertThat(full.getContentAsString()).contains("MCP_TRANSPORT_SESSION_LIMIT_REACHED");

        AtomicInteger deleteCalls = new AtomicInteger();
        MockHttpServletResponse deleted = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", "live-session", "DELETE"),
            deleted, (request, response) -> {
                deleteCalls.incrementAndGet();
                ((MockHttpServletResponse) response).setStatus(200);
            });
        assertThat(deleteCalls).hasValue(1);

        createSession(bounded, "qa-lead", "client-a", "replacement-session");
    }

    @Test
    void rejectsRedirectStatusSessionTransitionsAndReleasesCapacityOnChainFailure() throws Exception {
        McpProtocolSecurityFilter bounded = filter(Clock.fixed(NOW, ZoneOffset.UTC), 1);
        MockHttpServletResponse redirectCreation = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", null, "POST"),
            redirectCreation, (request, response) -> {
                ((MockHttpServletResponse) response).setStatus(300);
                ((MockHttpServletResponse) response).setHeader(HttpHeaders.MCP_SESSION_ID, "redirect-session");
            });
        MockHttpServletResponse absent = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", "redirect-session", "POST"),
            absent, counting(new AtomicInteger()));
        assertThat(absent.getStatus()).isEqualTo(404);

        createSession(bounded, "qa-lead", "client-a", "live-session");
        MockHttpServletResponse redirectDeletion = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", "live-session", "DELETE"),
            redirectDeletion, (request, response) -> ((MockHttpServletResponse) response).setStatus(300));
        MockHttpServletResponse stillFull = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", null, "POST"), stillFull,
            counting(new AtomicInteger()));
        assertThat(stillFull.getStatus()).isEqualTo(429);

        McpProtocolSecurityFilter exceptional = filter(Clock.fixed(NOW, ZoneOffset.UTC), 1);
        assertThatThrownBy(() -> exceptional.doFilter(
            request("qa-lead", "client-a", "2025-11-25", null, "POST"),
            new MockHttpServletResponse(),
            (request, response) -> {
                throw new IOException("expected");
            })).isInstanceOf(IOException.class).hasMessage("expected");
        createSession(exceptional, "qa-lead", "client-a", "after-failure");
    }

    private static MockHttpServletRequest request(String subject, String revision, String sessionId) {
        return request(subject, "test-client", revision, sessionId, "POST");
    }

    private static MockHttpServletRequest request(String subject, String clientId, String revision,
                                                  String sessionId, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/mcp");
        if (revision != null) {
            request.addHeader(HttpHeaders.PROTOCOL_VERSION, revision);
        }
        if (sessionId != null) {
            request.addHeader(HttpHeaders.MCP_SESSION_ID, sessionId);
        }
        Map<String, Object> attributes = Map.of(
            "iss", URI.create("https://issuer.example").toString(),
            "sub", subject,
            "client_id", clientId,
            "scope", "pockethive:mcp:discover");
        request.setUserPrincipal(authentication(subject, attributes));
        return request;
    }

    private static BearerTokenAuthentication authentication(String subject, Map<String, Object> attributes) {
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "redacted",
            Instant.now(), Instant.now().plusSeconds(60));
        List<GrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("SCOPE_pockethive:mcp:discover"));
        return new BearerTokenAuthentication(
            new DefaultOAuth2AuthenticatedPrincipal(subject, attributes, authorities), token, authorities);
    }

    private static FilterChain counting(AtomicInteger calls) {
        return (request, response) -> calls.incrementAndGet();
    }

    private static void createSession(McpProtocolSecurityFilter current, String subject,
                                      String clientId, String sessionId) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        current.doFilter(request(subject, clientId, "2025-11-25", null, "POST"), response,
            (request, created) -> {
                ((MockHttpServletResponse) created).setStatus(200);
                ((MockHttpServletResponse) created).setHeader(HttpHeaders.MCP_SESSION_ID, sessionId);
            });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static McpProtocolSecurityFilter filter(Clock clock, int maxSessions) {
        return new McpProtocolSecurityFilter(clock, Duration.ofMinutes(1), maxSessions);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
