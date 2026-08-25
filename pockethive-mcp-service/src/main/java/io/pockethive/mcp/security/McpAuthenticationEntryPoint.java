package io.pockethive.mcp.security;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Advertises the standards-defined OAuth discovery path for every MCP client. */
public final class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {
    static final String PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";
    private final String challenge;

    public McpAuthenticationEntryPoint(PocketHiveMcpProperties properties) {
        URI metadata = properties.pocketHiveIngress().resolve(PROTECTED_RESOURCE_METADATA_PATH);
        this.challenge = "Bearer resource_metadata=\"" + metadata + "\", scope=\""
            + String.join(" ", PocketHiveMcpScopes.COMPANION_ORDERED) + "\"";
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException failure) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"MCP_AUTHENTICATION_REQUIRED\"}");
    }
}
