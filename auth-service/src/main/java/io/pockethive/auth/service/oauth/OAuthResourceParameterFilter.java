package io.pockethive.auth.service.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import io.pockethive.auth.contract.PocketHiveBrowserAuth;
import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Responsibility: Enforce the exact OAuth protected-resource parameter at the HTTP boundary.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso; docs/architecture/AUTH_SERVICE_API_SPEC.md.
 */

public final class OAuthResourceParameterFilter extends OncePerRequestFilter {
    private final String expectedResource;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegisteredClientRepository clients;

    public OAuthResourceParameterFilter(String expectedResource, RegisteredClientRepository clients) {
        this.expectedResource = Objects.requireNonNull(expectedResource, "expectedResource");
        this.clients = clients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var client = singleNonBlank(request, "client_id") ? clients.findByClientId(request.getParameter("client_id")) : null;
        String resource = client != null && BrowserOAuthClients.isBrowser(client)
            ? PocketHiveBrowserAuth.RESOURCE : expectedResource;
        if ("GET".equals(request.getMethod()) && "/oauth/authorize".equals(path)) {
            if (!singleNonBlank(request, "client_id")
                || !singleEquals(request, "resource", resource)
                || !singleNonBlank(request, "redirect_uri")
                || !singleNonBlank(request, "state")
                || !singleEquals(request, "code_challenge_method", "S256")) {
                error(response, "invalid_request",
                    "client_id, resource, registered redirect_uri, state, and PKCE S256 are required");
                return;
            }
        } else if (PocketHiveDeviceAuth.AUTHORIZATION_PATH.equals(path)
            && (!singleEquals(request, "resource", PocketHiveBrowserAuth.RESOURCE)
                || !singleEquals(request, "scope", PocketHiveBrowserAuth.SCOPE))) {
            error(response, "invalid_request", "Device login requires the exact browser resource and scope");
            return;
        } else if ("/oauth/token".equals(path) && !singleEquals(request, "resource", resource)) {
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

    private void error(HttpServletResponse response, String code, String description) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", code, "error_description", description));
    }
}
