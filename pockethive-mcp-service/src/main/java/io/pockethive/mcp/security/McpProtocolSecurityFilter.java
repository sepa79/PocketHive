package io.pockethive.mcp.security;

import io.modelcontextprotocol.spec.HttpHeaders;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.PrincipalKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class McpProtocolSecurityFilter extends OncePerRequestFilter {
    private final String requiredProtocolRevision;
    private final Clock clock;
    private final Duration sessionTtl;
    private final Semaphore sessionCapacity;
    private final Map<String, SessionBinding> sessionPrincipals = new ConcurrentHashMap<>();

    public McpProtocolSecurityFilter(String requiredProtocolRevision, Clock clock, Duration sessionTtl,
                                     int maxSessions) {
        if (!PocketHiveMcpProperties.REQUIRED_PROTOCOL_REVISION.equals(requiredProtocolRevision)) {
            throw new IllegalArgumentException("MCP_PROTOCOL_REVISION_UNSUPPORTED");
        }
        if (clock == null || sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()
            || maxSessions < 1) {
            throw new IllegalArgumentException("MCP_TRANSPORT_SESSION_CONFIGURATION_INVALID");
        }
        this.requiredProtocolRevision = requiredProtocolRevision;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
        this.sessionCapacity = new Semaphore(maxSessions);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String protocolRevision = request.getHeader(HttpHeaders.PROTOCOL_VERSION);
        if (!requiredProtocolRevision.equals(protocolRevision)) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "MCP_PROTOCOL_REVISION_UNSUPPORTED");
            return;
        }
        SessionIdentity principal = principal(request);
        String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId != null && !sessionId.isBlank()) {
            SessionBinding bound = sessionPrincipals.get(sessionId);
            if (bound == null) {
                reject(response, HttpServletResponse.SC_NOT_FOUND, "MCP_TRANSPORT_SESSION_NOT_FOUND");
                return;
            }
            if (!bound.identity().equals(principal)) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "MCP_TRANSPORT_SESSION_PRINCIPAL_MISMATCH");
                return;
            }
            if (!"DELETE".equals(request.getMethod()) && !clock.instant().isBefore(bound.expiresAt())) {
                reject(response, HttpServletResponse.SC_NOT_FOUND, "MCP_TRANSPORT_SESSION_EXPIRED");
                return;
            }
        }

        boolean initializationCapacity = sessionId == null && "POST".equals(request.getMethod());
        if (initializationCapacity && !sessionCapacity.tryAcquire()) {
            reject(response, 429, "MCP_TRANSPORT_SESSION_LIMIT_REACHED");
            return;
        }
        boolean capacityTransferred = false;
        try {
            chain.doFilter(request, response);

            String createdSessionId = response.getHeader(HttpHeaders.MCP_SESSION_ID);
            if (initializationCapacity && createdSessionId != null && !createdSessionId.isBlank()
                && response.getStatus() >= 200 && response.getStatus() < 300) {
                SessionBinding binding = new SessionBinding(principal, clock.instant().plus(sessionTtl));
                capacityTransferred = sessionPrincipals.putIfAbsent(createdSessionId, binding) == null;
            }
            if ("DELETE".equals(request.getMethod()) && sessionId != null
                && response.getStatus() >= 200 && response.getStatus() < 300
                && sessionPrincipals.remove(sessionId) != null) {
                sessionCapacity.release();
            }
        } finally {
            if (initializationCapacity && !capacityTransferred) {
                sessionCapacity.release();
            }
        }
    }

    private static SessionIdentity principal(HttpServletRequest request) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        BearerTokenAuthentication authentication = current instanceof BearerTokenAuthentication bearer
            ? bearer
            : request.getUserPrincipal() instanceof BearerTokenAuthentication bearer ? bearer : null;
        if (authentication == null) {
            throw new IllegalStateException("MCP_AUTHENTICATION_REQUIRED");
        }
        Object issuer = authentication.getTokenAttributes().get("iss");
        Object subject = authentication.getTokenAttributes().get("sub");
        Object clientId = authentication.getTokenAttributes().get("client_id");
        String client = String.valueOf(clientId);
        if (clientId == null || client.isBlank()) {
            throw new IllegalStateException("MCP_AUTHENTICATED_CLIENT_REQUIRED");
        }
        return new SessionIdentity(new PrincipalKey(URI.create(String.valueOf(issuer)), String.valueOf(subject)),
            client);
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    private record SessionIdentity(PrincipalKey principal, String clientId) {
    }

    private record SessionBinding(SessionIdentity identity, Instant expiresAt) {
    }
}
