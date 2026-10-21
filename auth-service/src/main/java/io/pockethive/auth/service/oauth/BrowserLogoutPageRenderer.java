package io.pockethive.auth.service.oauth;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Responsibility: Render the escaped central browser sign-out confirmation.
 * Must not: Resolve redirects, authenticate users or invalidate sessions.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
@Component
public final class BrowserLogoutPageRenderer {
    public String render(String issuer, String clientId, String name, String parameter, String csrf) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sign out of PocketHive</title><link rel="stylesheet" href="%s/oauth/pockethive-auth.css"></head>
            <body><main class="auth-shell"><section class="auth-card">
            <h1>Sign out of shared login?</h1><p>You have signed out of %s. End the shared browser login too.</p>
            <p>Other already signed-in tools keep their existing session until sign-out or expiry.</p>
            <form class="auth-form" method="post" action="%s/oauth/browser/logout">
            <input type="hidden" name="client_id" value="%s"><input type="hidden" name="%s" value="%s">
            <button class="auth-button auth-button--primary" type="submit">Sign out of shared login</button>
            </form></section></main></body></html>
            """.formatted(escape(issuer), escape(name), escape(issuer), escape(clientId), escape(parameter), escape(csrf));
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value); }
}
