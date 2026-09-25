package io.pockethive.tcpmock.config;

import io.pockethive.tcpmock.model.AdministrationAuthProvider;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Responsibility: own the explicit administration provider selection. Must not: choose a substitute
 * provider or acquire credentials. Contract: RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@ConfigurationProperties("tcp-mock.auth")
public record TcpMockAuthSelection(AdministrationAuthProvider provider) {
  public TcpMockAuthSelection {
    Objects.requireNonNull(provider, "tcp-mock.auth.provider is required");
  }
}
