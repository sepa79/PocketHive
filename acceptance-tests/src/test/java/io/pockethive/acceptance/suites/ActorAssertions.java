package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.AuthApi;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import java.util.HashSet;
import java.util.List;

/**
 * Responsibility: assert the selected actor's identity and exact PocketHive grant set.
 * Must not: authenticate, provision users or calculate authorization decisions.
 * Contract: docs/architecture/acceptance-tests.md#scoped-runner-acceptance-slice.
 */
final class ActorAssertions {
  private ActorAssertions() {}
  static void requireGrants(ApiRun run, String username, List<AuthGrantDto> expected) throws Exception {
    var profile = new AuthApi(run.http).profile(run.token);
    run.evidence.record("actor-profile", profile);
    assertEquals(username, profile.username());
    assertTrue(profile.active(), "Test actor must be active");
    var actual = profile.grants().stream().filter(grant -> grant.product() == AuthProduct.POCKETHIVE).toList();
    assertEquals(expected.size(), actual.size());
    assertEquals(new HashSet<>(expected), new HashSet<>(actual));
  }
}
