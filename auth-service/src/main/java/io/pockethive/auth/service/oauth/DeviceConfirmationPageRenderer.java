package io.pockethive.auth.service.oauth;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Responsibility: Render escaped PH device-confirmation and completion pages.
 * Must not: Validate consent, authenticate users or mutate authorization state.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md.
 */
@Component
final class DeviceConfirmationPageRenderer {
    String consent(String issuer, String action, String clientId, String name, String state,
                   String userCode, String scope, String csrfName, String csrfToken) {
        String hidden = """
            <input type="hidden" name="client_id" value="%s">
            <input type="hidden" name="state" value="%s">
            <input type="hidden" name="user_code" value="%s">
            <input type="hidden" name="%s" value="%s">
            """.formatted(escape(clientId), escape(state), escape(userCode), escape(csrfName), escape(csrfToken));
        return page(issuer, "Confirm sign in", """
            <h1>Sign in to %s?</h1>
            <p>Confirm only if you just started sign-in in this tool.</p>
            <p>Confirmation code: <strong>%s</strong></p>
            <form class="auth-form" method="post" action="%s">%s
            <input type="hidden" name="scope" value="%s">
            <button class="auth-button auth-button--primary" type="submit">Confirm sign in</button></form>
            <form class="auth-form" method="post" action="%s">%s
            <button class="auth-button auth-button--secondary" type="submit">Deny</button></form>
            """.formatted(escape(name), escape(userCode), escape(action), hidden, escape(scope), escape(action), hidden));
    }

    String complete(String issuer) {
        return page(issuer, "Confirmation complete", """
            <h1>Confirmation complete</h1>
            <p>Return to the original tool. It will finish sign-in automatically.</p>
            <p>You can close this tab.</p>
            """);
    }

    private static String page(String issuer, String title, String content) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s</title><link rel="stylesheet" href="%s/oauth/pockethive-auth.css"></head>
            <body><main class="auth-shell"><section class="auth-card">%s</section></main></body></html>
            """.formatted(escape(title), escape(issuer), content);
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value); }
}
