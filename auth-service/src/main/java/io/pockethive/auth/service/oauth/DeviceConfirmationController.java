package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import io.pockethive.auth.service.config.AuthServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Responsibility: Map first-party device-confirmation presentation requests.
 * Must not: Validate OAuth consent state, accept return URLs or issue tokens.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md.
 */
@Controller
final class DeviceConfirmationController {
    private final BrowserOAuthClients clients;
    private final DeviceConfirmationPageRenderer pages;
    private final String issuer;

    DeviceConfirmationController(BrowserOAuthClients clients, DeviceConfirmationPageRenderer pages,
                                 AuthServiceProperties properties) {
        this.clients = clients;
        this.pages = pages;
        issuer = properties.getOauth().getIssuer().toString();
    }

    @GetMapping(value = PocketHiveDeviceAuth.CONSENT_PATH, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String consent(@RequestParam("client_id") String clientId, @RequestParam("state") String state,
                   @RequestParam("user_code") String userCode, @RequestParam("scope") String scope,
                   HttpServletRequest request, HttpServletResponse response) {
        var client = clients.requireDeviceClient(clientId);
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        response.setHeader("Cache-Control", "no-store");
        return pages.consent(issuer, issuer + PocketHiveDeviceAuth.VERIFICATION_PATH, clientId,
            client.getClientName(), state, userCode, scope, csrf.getParameterName(), csrf.getToken());
    }

    @GetMapping(value = PocketHiveDeviceAuth.COMPLETE_PATH, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String complete(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return pages.complete(issuer);
    }
}
