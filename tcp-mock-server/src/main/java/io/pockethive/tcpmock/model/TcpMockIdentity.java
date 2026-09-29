package io.pockethive.tcpmock.model;

/**
 * Responsibility: project provider-qualified authenticated administration identity. Must not:
 * authenticate credentials, grant permissions or maintain user state. Contract:
 * docs/tcp-mock/legacy-workspaces.md#authentication-provider-and-ownership.
 */
public record TcpMockIdentity(
    AdministrationAuthProvider provider, String subject, String displayName) {
  public String ownerId() {
    return provider.name() + ":" + subject;
  }
}
