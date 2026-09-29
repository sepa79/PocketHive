package io.pockethive.tcpmock.config;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.tcpmock.model.TcpMockIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project identity from the explicitly selected authenticated provider. Must not:
 * parse credentials, choose providers from request content or grant permissions. Contract:
 * RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@Component
public final class TcpMockIdentityResolver {
  private final TcpMockAuthSelection selection;

  public TcpMockIdentityResolver(TcpMockAuthSelection selection) {
    this.selection = selection;
  }

  public TcpMockIdentity resolve(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated())
      throw new IllegalStateException("Authenticated identity required");
    return switch (selection.provider()) {
      case NATIVE -> {
        var user = (UserDetails) authentication.getPrincipal();
        yield new TcpMockIdentity(selection.provider(), user.getUsername(), user.getUsername());
      }
      case POCKETHIVE -> {
        var user = (AuthenticatedUserDto) authentication.getPrincipal();
        yield new TcpMockIdentity(selection.provider(), user.id().toString(), user.displayName());
      }
    };
  }
}
