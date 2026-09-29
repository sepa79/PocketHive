package io.pockethive.tcpmock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Responsibility: validate the explicitly configured native administration account. Must not:
 * resolve PocketHive users or print credentials. Contract: RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@ConfigurationProperties("tcp-mock.auth.native")
public record TcpMockNativeAuthProperties(String username, String password) {
  public TcpMockNativeAuthProperties {
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalArgumentException(
          "Native administration username and password are required");
    }
  }

  @Override
  public String toString() {
    return "TcpMockNativeAuthProperties[credentials=redacted]";
  }
}
