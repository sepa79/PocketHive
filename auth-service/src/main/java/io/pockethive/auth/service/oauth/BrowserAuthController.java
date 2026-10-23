package io.pockethive.auth.service.oauth;

import io.pockethive.auth.service.config.AuthServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Responsibility: Map first-party browser config and CSRF-protected central logout.
 * Must not: Issue tokens, authenticate usernames or accept arbitrary return URLs.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
@Controller
public final class BrowserAuthController {
    private final BrowserOAuthClients clients;
    private final BrowserLogoutPageRenderer pages;
    private final String issuer;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    public BrowserAuthController(BrowserOAuthClients clients, BrowserLogoutPageRenderer pages,
                                  AuthServiceProperties properties) {
        this.clients = clients;
        this.pages = pages;
        issuer = properties.getOauth().getIssuer().toString();
    }

    @GetMapping("/api/auth/browser/config")
    @ResponseBody
    public Map<String, String> config(@RequestParam("client_id") String clientId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return clients.config(clientId);
    }

    @GetMapping(value = "/oauth/browser/logout", produces = MediaType.TEXT_HTML_VALUE)
    public void logoutPage(@RequestParam("client_id") String clientId, HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        var client = clients.requireRedirectClient(clientId);
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(pages.render(issuer, client.getClientId(), client.getClientName(),
            csrf.getParameterName(), csrf.getToken()));
    }

    @PostMapping("/oauth/browser/logout")
    public void logout(@RequestParam("client_id") String clientId, HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        String callback = clients.requireRedirectClient(clientId).getRedirectUris().iterator().next();
        logout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(callback + "?signed_out=1");
    }
}
