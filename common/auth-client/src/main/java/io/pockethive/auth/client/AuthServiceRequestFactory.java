package io.pockethive.auth.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Responsibility: Configure bounded-time HTTP connections without credential-bearing redirects.
 * Must not: Resolve alternative endpoints, retry or decide identity.
 * Contract: docs/architecture/BROWSER_DEVICE_LOGIN.md#shared-consumer-boundary.
 */
final class AuthServiceRequestFactory {
    private AuthServiceRequestFactory() {}
    static JdkClientHttpRequestFactory create(Duration connectTimeout, Duration readTimeout) {
        if (connectTimeout.toMillis() <= 0 || readTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Auth timeouts must be positive");
        }
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(connectTimeout).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
