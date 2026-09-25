package io.pockethive.tcpmock.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Responsibility: validate explicit administration authentication settings. Must not: acquire
 * sessions, authorize callers or affect TCP traffic. Contract: RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@ConfigurationProperties("pockethive.auth")
public record TcpMockAuthProperties(URI serviceUrl, Duration connectTimeout, Duration readTimeout) {
  public TcpMockAuthProperties {
    if (serviceUrl == null
        || serviceUrl.getHost() == null
        || !("http".equals(serviceUrl.getScheme()) || "https".equals(serviceUrl.getScheme()))
        || serviceUrl.getUserInfo() != null
        || serviceUrl.getQuery() != null
        || serviceUrl.getFragment() != null) {
      throw new IllegalArgumentException(
          "pockethive.auth.service-url must be an explicit HTTP(S) endpoint");
    }
    if (connectTimeout == null
        || connectTimeout.isNegative()
        || connectTimeout.isZero()
        || readTimeout == null
        || readTimeout.isNegative()
        || readTimeout.isZero()) {
      throw new IllegalArgumentException("Authentication timeouts must be positive");
    }
  }
}
