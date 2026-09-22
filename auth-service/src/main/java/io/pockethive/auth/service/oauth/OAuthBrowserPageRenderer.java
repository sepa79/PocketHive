package io.pockethive.auth.service.oauth;

import io.pockethive.auth.contract.PocketHiveMcpScopes;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Responsibility: Render escaped PocketHive-themed OAuth browser pages.
 * Must not: Bypass canonical scope policy, client authentication, or Spring Authorization Server contracts.
 * Contract: docs/architecture/AUTH_SERVICE_API_SPEC.md and docs/AUTH-BEHAVIOR.md.
 */

@Component
final class OAuthBrowserPageRenderer {

    String login(String action, String csrfParameter, String csrfToken, String stylesheet, String logo) {
        return page("PocketHive sign in", stylesheet, """
            <section class="auth-card" aria-labelledby="auth-title">
              %s
              <div class="auth-intro">
                <p class="auth-eyebrow">Secure environment access</p>
                <h1 id="auth-title">Sign in to PocketHive</h1>
                <p>Authenticate with the selected PocketHive environment to continue in your MCP client.</p>
              </div>
              <form class="auth-form" method="post" action="%s">
                <label class="auth-field" for="username">
                  <span>Configured username</span>
                  <input id="username" name="username" required autocomplete="username" autofocus>
                  <small>Use the username supplied by your PocketHive administrator.</small>
                </label>
                <input type="hidden" name="%s" value="%s">
                <button class="auth-button auth-button--primary" type="submit">Sign in</button>
              </form>
              <p class="auth-assurance">Your session is protected by the selected environment and stored by your MCP client.</p>
            </section>
            """.formatted(brand(logo), escape(action), escape(csrfParameter), escape(csrfToken)));
    }

    String consent(String action, String clientId, String clientName, String resource, String state,
                   String csrfParameter, String csrfToken, List<String> scopes, String stylesheet, String logo) {
        String scopeInputs = scopes.stream().map(this::scopeInput).reduce("", String::concat);
        return page("PocketHive access", stylesheet, """
            <section class="auth-card" aria-labelledby="auth-title">
              %s
              <div class="auth-intro">
                <p class="auth-eyebrow">Authorization request</p>
                <h1 id="auth-title">Allow PocketHive access?</h1>
                <p>You are authorizing the selected PocketHive environment for this client.</p>
              </div>
              <dl class="auth-context">
                <div><dt>Client</dt><dd>%s</dd></div>
                <div><dt>Resource</dt><dd><code>%s</code></dd></div>
              </dl>
              <form class="auth-form" method="post" action="%s">
                <input type="hidden" name="client_id" value="%s">
                <input type="hidden" name="state" value="%s">
                <input type="hidden" name="%s" value="%s">
                <fieldset class="auth-scopes">
                  <legend>Requested permissions</legend>
                  %s
                </fieldset>
                <div class="auth-actions">
                  <button class="auth-button auth-button--primary" name="consent_action" value="approve" type="submit">Allow</button>
                  <button class="auth-button auth-button--secondary" name="consent_action" value="cancel" type="submit">Decline</button>
                </div>
              </form>
              <p class="auth-assurance">Only the permissions listed above will be granted. This device session renews securely without reopening sign-in for each action.</p>
            </section>
            """.formatted(brand(logo), escape(clientName), escape(resource), escape(action),
            escape(clientId), escape(state), escape(csrfParameter), escape(csrfToken), scopeInputs));
    }

    String authorizationFailure(String code, String message, String stylesheet, String logo) {
        return page("PocketHive authorization", stylesheet, """
            <section class="auth-card" aria-labelledby="auth-title">
              %s
              <div class="auth-intro">
                <p class="auth-eyebrow auth-eyebrow--danger">Authorization interrupted</p>
                <h1 id="auth-title">Authorization could not continue</h1>
                <p>%s</p>
              </div>
              <div class="auth-error" role="alert">
                <span>Error code</span>
                <code>%s</code>
              </div>
              <p class="auth-assurance">No access was granted. You can close this page and reconnect the PocketHive MCP environment from your client.</p>
            </section>
            """.formatted(brand(logo), escape(message), escape(code)));
    }

    private String scopeInput(String scope) {
        String escaped = escape(scope);
        return """
            <label class="auth-scope">
              <input type="checkbox" name="scope" value="%s" checked>
              <span><strong>%s</strong><small><code>%s</code></small></span>
            </label>
            """.formatted(escaped, escape(scopeDescription(scope)), escaped);
    }

    private static String page(String title, String stylesheet, String content) {
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <link rel="stylesheet" href="%s">
              </head>
              <body>
                <main class="auth-shell">%s</main>
              </body>
            </html>
            """.formatted(escape(title), escape(stylesheet), content);
    }

    private static String brand(String logo) {
        return """
            <header class="auth-brand">
              <img class="auth-brand__logo" src="%s" alt="PocketHive">
              <span class="auth-brand__product">MCP access</span>
            </header>
            """.formatted(escape(logo));
    }

    private static String scopeDescription(String scope) {
        return switch (scope) {
            case PocketHiveMcpScopes.DISCOVER -> "Discover PocketHive capabilities and connected skills";
            case PocketHiveMcpScopes.READ -> "Read PocketHive runtime and scenario data";
            case PocketHiveMcpScopes.OPERATE -> "Start, stop, and operate explicit PocketHive targets";
            case PocketHiveMcpScopes.AUTHOR -> "Prepare and validate Scenario Bundles";
            case PocketHiveMcpScopes.PUBLISH -> "Publish an explicitly validated Scenario Bundle";
            case PocketHiveMcpScopes.CLEANUP -> "Execute an approved runtime cleanup plan";
            default -> scope;
        };
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
