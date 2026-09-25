package io.pockethive.tcpmock.controller;

import io.pockethive.tcpmock.config.TcpMockAuthSelection;
import io.pockethive.tcpmock.config.TcpMockIdentityResolver;
import io.pockethive.tcpmock.model.TcpMockIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: expose selected provider and authenticated identity projections. Must not:
 * resolve credentials, create users or grant permissions. Contract: RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
@RestController
public class CurrentUserController {
  private final TcpMockAuthSelection selection;
  private final TcpMockIdentityResolver identities;

  public CurrentUserController(TcpMockAuthSelection selection, TcpMockIdentityResolver identities) {
    this.selection = selection;
    this.identities = identities;
  }

  @GetMapping("/api/auth/config")
  public TcpMockAuthSelection configuration() {
    return selection;
  }

  @GetMapping("/api/auth/me")
  public TcpMockIdentity current(Authentication authentication) {
    return identities.resolve(authentication);
  }
}
