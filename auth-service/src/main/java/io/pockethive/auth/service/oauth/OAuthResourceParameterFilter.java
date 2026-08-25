package io.pockethive.auth.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

public final class OAuthResourceParameterFilter extends OncePerRequestFilter {
    private final String expectedResource;
    private final RegisteredClientRepository clients;
    private final ObjectMapper mapper = new ObjectMapper();

    public OAuthResourceParameterFilter(String expectedResource, RegisteredClientRepository clients) {
        this.expectedResource = Objects.requireNonNull(expectedResource, "expectedResource");
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("GET".equals(request.getMethod()) && "/oauth/authorize".equals(path)) {
            if (!singleEquals(request, "resource", expectedResource)
                || !singleRegisteredRedirect(request)
                || !singleNonBlank(request, "state")
                || !singleEquals(request, "code_challenge_method", "S256")) {
                error(response, "invalid_request",
                    "resource, exact registered redirect_uri, state, and PKCE S256 are required");
                return;
            }
        } else if ("/oauth/token".equals(path) && !singleEquals(request, "resource", expectedResource)) {
            error(response, "invalid_request", "resource must match the authorization request");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean singleEquals(HttpServletRequest request, String name, String expected) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 && expected.equals(values[0]);
    }

    private static boolean singleNonBlank(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 && !values[0].isBlank();
    }

    private boolean singleRegisteredRedirect(HttpServletRequest request) {
        String[] clientIds = request.getParameterValues("client_id");
        String[] redirectUris = request.getParameterValues("redirect_uri");
        if (clientIds == null || clientIds.length != 1 || clientIds[0].isBlank()
            || redirectUris == null || redirectUris.length != 1 || redirectUris[0].isBlank()) {
            return false;
        }
        RegisteredClient client = clients.findByClientId(clientIds[0]);
        return client != null && client.getRedirectUris().contains(redirectUris[0]);
    }

    private void error(HttpServletResponse response, String code, String description) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", code, "error_description", description));
    }
}
