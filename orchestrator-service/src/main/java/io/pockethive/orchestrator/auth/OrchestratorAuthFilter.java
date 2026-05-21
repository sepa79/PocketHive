package io.pockethive.orchestrator.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceClientException;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class OrchestratorAuthFilter extends OncePerRequestFilter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthServiceClient authServiceClient;
    private final OrchestratorAuthorization authorization;

    public OrchestratorAuthFilter(AuthServiceClient authServiceClient, OrchestratorAuthorization authorization) {
        this.authServiceClient = authServiceClient;
        this.authorization = authorization;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Missing Authorization header");
            return;
        }

        final AuthenticatedUserDto user;
        try {
            user = authServiceClient.resolve(authorizationHeader);
        } catch (AuthServiceClientException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED.value()) {
                writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or expired bearer token");
                return;
            }
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "Auth service resolve failed");
            return;
        } catch (RuntimeException e) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "Auth service unavailable");
            return;
        }

        String path = request.getRequestURI();
        if (!authorization.isAllowed(user, request.getMethod(), path)) {
            writeError(response, HttpStatus.FORBIDDEN, authorization.denialMessage(request.getMethod(), path));
            return;
        }

        try {
            OrchestratorCurrentUserHolder.set(user);
            filterChain.doFilter(request, response);
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MAPPER.writeValue(response.getWriter(), Map.of("message", message));
    }
}
