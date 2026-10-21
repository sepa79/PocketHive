package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import io.pockethive.auth.service.config.AuthServiceProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

class BrowserSessionCookieCustomizerTest {
    @Test
    void publicHttpsSetsSecureEvenWhenTheInternalProxyUsesHttp() {
        var properties = new AuthServiceProperties();
        properties.getOauth().setIssuer(URI.create("https://auth.example/auth-service"));
        var factory = new TomcatServletWebServerFactory();
        factory.getSession().getCookie().setName("selected-cookie");
        new BrowserSessionCookieCustomizer(properties).customize(factory);
        assertThat(factory.getSession().getCookie().getSecure()).isTrue();
        assertThat(factory.getSession().getCookie().getName()).isEqualTo("selected-cookie");
        properties.getOauth().setIssuer(URI.create("http://localhost:8089/auth-service"));
        new BrowserSessionCookieCustomizer(properties).customize(factory);
        assertThat(factory.getSession().getCookie().getSecure()).isFalse();
    }
}
