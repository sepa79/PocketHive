package io.pockethive.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.HttpHeaders;
import jakarta.servlet.FilterChain;
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
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;

class McpProtocolSecurityFilterTest {
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private final McpProtocolSecurityFilter filter = filter(Clock.fixed(NOW, ZoneOffset.UTC), 10);

    @Test
    void rejectsMissingOrUnsupportedRevisionBeforeTheTransport() throws Exception {
        for (String revision : new String[] {null, "2026-07-28"}) {
            MockHttpServletRequest request = request("qa-lead", revision, null);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicInteger calls = new AtomicInteger();

            filter.doFilter(request, response, counting(calls));

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("MCP_PROTOCOL_REVISION_UNSUPPORTED");
            assertThat(calls).hasValue(0);
        }
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
    void boundsSessionsFailsClosedAtExpiryAndReleasesCapacityOnlyAfterAuthenticatedDelete() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        McpProtocolSecurityFilter bounded = filter(clock, 1);
        createSession(bounded, "qa-lead", "client-a", "expired-session");
        clock.current = NOW.plusSeconds(61);

        MockHttpServletResponse expired = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", "expired-session", "POST"),
            expired, counting(new AtomicInteger()));
        assertThat(expired.getStatus()).isEqualTo(404);
        assertThat(expired.getContentAsString()).contains("MCP_TRANSPORT_SESSION_EXPIRED");

        MockHttpServletResponse full = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", null, "POST"), full,
            counting(new AtomicInteger()));
        assertThat(full.getStatus()).isEqualTo(429);
        assertThat(full.getContentAsString()).contains("MCP_TRANSPORT_SESSION_LIMIT_REACHED");

        AtomicInteger deleteCalls = new AtomicInteger();
        MockHttpServletResponse deleted = new MockHttpServletResponse();
        bounded.doFilter(request("qa-lead", "client-a", "2025-11-25", "expired-session", "DELETE"),
            deleted, (request, response) -> {
                deleteCalls.incrementAndGet();
                ((MockHttpServletResponse) response).setStatus(200);
            });
        assertThat(deleteCalls).hasValue(1);

        createSession(bounded, "qa-lead", "client-a", "replacement-session");
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
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "redacted",
            Instant.now(), Instant.now().plusSeconds(60));
        List<GrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("SCOPE_pockethive:mcp:discover"));
        request.setUserPrincipal(new BearerTokenAuthentication(
            new DefaultOAuth2AuthenticatedPrincipal(subject, attributes, authorities), token, authorities));
        return request;
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
        return new McpProtocolSecurityFilter("2025-11-25", clock, Duration.ofMinutes(1), maxSessions);
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
