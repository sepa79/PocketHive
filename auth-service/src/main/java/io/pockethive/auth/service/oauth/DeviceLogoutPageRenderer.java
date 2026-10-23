package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveDeviceAuth;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Responsibility: Render escaped callback-free PH logout confirmation and completion pages.
 * Must not: Validate clients, invalidate sessions, revoke tokens or resolve app destinations.
 * Contract: RESP-BROWSER-DEVICE-LOGIN — docs/architecture/runtime-responsibilities.md#resp-browser-device-login.
 */
@Component
final class DeviceLogoutPageRenderer {
    String confirmation(String issuer, String clientId, String name, String csrfName, String csrfToken) {
        return page(issuer, "Sign out of PocketHive", """
            <h1>Sign out of shared login?</h1>
            <p>End the shared PocketHive browser login used by %s.</p>
            <p>Other already signed-in tools keep their existing session until sign-out or expiry.</p>
            <form class="auth-form" method="post" action="%s">
            <input type="hidden" name="client_id" value="%s">
            <input type="hidden" name="%s" value="%s">
            <button class="auth-button auth-button--primary" type="submit">Sign out of shared login</button>
            </form><p>To keep the shared login, close this tab without confirming.</p>
            """.formatted(escape(name), escape(issuer + PocketHiveDeviceAuth.LOGOUT_PATH), escape(clientId),
                escape(csrfName), escape(csrfToken)));
    }

    String complete(String issuer) {
        return page(issuer, "Signed out of PocketHive", """
            <h1>Shared login signed out</h1>
            <p>The shared PocketHive browser login has ended.</p>
            <p>Other already signed-in tools keep their existing session until sign-out or expiry.</p>
            <p>Return to the original tool. You can close this tab.</p>
            """);
    }

    private static String page(String issuer, String title, String content) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s</title><link rel="stylesheet" href="%s"></head>
            <body><main class="auth-shell"><section class="auth-card">%s</section></main></body></html>
            """.formatted(escape(title), escape(issuer + OAuthBrowserController.STYLESHEET_PATH), content);
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value); }
}
