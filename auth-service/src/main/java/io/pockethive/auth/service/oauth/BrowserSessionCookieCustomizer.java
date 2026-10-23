package io.pockethive.auth.service.oauth;

import io.pockethive.auth.service.config.AuthServiceProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.AbstractServletWebServerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Bind the SSO cookie Secure flag to the canonical public issuer transport.
 * Must not: Infer TLS from an internal proxy connection or authenticate sessions.
 * Contract: RESP-BROWSER-SSO — docs/architecture/runtime-responsibilities.md#resp-browser-sso.
 */
@Component
public final class BrowserSessionCookieCustomizer
    implements WebServerFactoryCustomizer<AbstractServletWebServerFactory>, Ordered {
    private final AuthServiceProperties properties;

    public BrowserSessionCookieCustomizer(AuthServiceProperties properties) { this.properties = properties; }

    @Override
    public void customize(AbstractServletWebServerFactory factory) {
        factory.getSession().getCookie().setSecure("https".equalsIgnoreCase(properties.getOauth().getIssuer().getScheme()));
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
