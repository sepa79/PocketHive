package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import io.pockethive.auth.service.config.AuthServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Responsibility: Map device-client logout discovery and CSRF-protected central session sign-out.
 * Must not: Issue or revoke tokens, infer public URLs or accept app callbacks.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
@Controller
final class DeviceLogoutController {
    private final BrowserOAuthClients clients;
    private final DeviceLogoutPageRenderer pages;
    private final String issuer;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    DeviceLogoutController(BrowserOAuthClients clients, DeviceLogoutPageRenderer pages,
                           AuthServiceProperties properties) {
        this.clients = clients;
        this.pages = pages;
        issuer = properties.getOauth().getIssuer().toString();
    }

    @GetMapping(PocketHiveDeviceAuth.CONFIG_PATH)
    @ResponseBody
    Map<String, String> config(@RequestParam("client_id") String clientId, HttpServletResponse response) {
        var client = clients.requireDeviceClient(clientId);
        response.setHeader("Cache-Control", "no-store");
        String logoutUri = UriComponentsBuilder.fromUriString(issuer).path(PocketHiveDeviceAuth.LOGOUT_PATH)
            .queryParam("client_id", client.getClientId()).build().toUriString();
        return Map.of("logoutUri", logoutUri);
    }

    @GetMapping(value = PocketHiveDeviceAuth.LOGOUT_PATH, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String confirmation(@RequestParam("client_id") String clientId, HttpServletRequest request,
                        HttpServletResponse response) {
        var client = clients.requireDeviceClient(clientId);
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        response.setHeader("Cache-Control", "no-store");
        return pages.confirmation(issuer, client.getClientId(), client.getClientName(),
            csrf.getParameterName(), csrf.getToken());
    }

    @PostMapping(value = PocketHiveDeviceAuth.LOGOUT_PATH, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String logout(@RequestParam("client_id") String clientId, HttpServletRequest request,
                  HttpServletResponse response) {
        clients.requireDeviceClient(clientId);
        logout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        response.setHeader("Cache-Control", "no-store");
        return pages.complete(issuer);
    }
}
