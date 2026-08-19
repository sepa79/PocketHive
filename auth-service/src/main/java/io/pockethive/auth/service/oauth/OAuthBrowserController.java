package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.service.config.AuthServiceProperties;
import io.pockethive.auth.service.domain.StoredUser;
import io.pockethive.auth.service.service.InMemoryUserStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public final class OAuthBrowserController {
    private final AuthServiceProperties properties;
    private final InMemoryUserStore users;
    private final OAuthBrowserPageRenderer pages;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public OAuthBrowserController(AuthServiceProperties properties, InMemoryUserStore users,
                                  OAuthBrowserPageRenderer pages) {
        this.properties = properties;
        this.users = users;
        this.pages = pages;
    }

    @GetMapping(value = "/oauth/dev/login", produces = MediaType.TEXT_HTML_VALUE)
    void loginPage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        requireDev();
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String action = publicEndpoint("/oauth/dev/login");
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(pages.login(action, csrf.getParameterName(), csrf.getToken(),
            publicEndpoint("/oauth/pockethive-auth.css"), publicEndpoint("/oauth/logo.svg")));
    }

    @PostMapping("/oauth/dev/login")
    void login(@RequestParam("username") String username, HttpServletRequest request,
               HttpServletResponse response) throws IOException {
        requireDev();
        StoredUser user = users.findByUsername(username).filter(StoredUser::active)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unknown or inactive user"));
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
            user.username(), "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        var saved = new org.springframework.security.web.savedrequest.HttpSessionRequestCache()
            .getRequest(request, response);
        response.sendRedirect(saved == null ? "/" : saved.getRedirectUrl());
    }

    @GetMapping(value = "/oauth/consent", produces = MediaType.TEXT_HTML_VALUE)
    void consent(@RequestParam("client_id") String clientId,
                 @RequestParam("state") String state,
                 @RequestParam(name = "scope", required = false) List<String> scope,
                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        List<String> scopes = scope == null ? List.of() : scope.stream()
            .flatMap(value -> java.util.Arrays.stream(value.split(" "))).filter(value -> !value.isBlank()).toList();
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(pages.consent(
            publicEndpoint("/oauth/authorize"), clientId, properties.getOauth().getResource().toString(), state,
            csrf.getParameterName(), csrf.getToken(), scopes, publicEndpoint("/oauth/pockethive-auth.css"),
            publicEndpoint("/oauth/logo.svg")));
    }

    private String publicEndpoint(String path) {
        return properties.getOauth().getIssuer() + path;
    }

    private void requireDev() {
        if (properties.getProvider() != AuthProvider.DEV) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED, "DEV login is disabled");
        }
    }
}
