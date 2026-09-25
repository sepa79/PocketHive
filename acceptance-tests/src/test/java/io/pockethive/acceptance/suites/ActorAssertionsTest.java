package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.config.ApiTarget;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.auth.contract.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActorAssertionsTest {
  @TempDir Path reports;
  private static final AuthGrantDto VIEW = new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.VIEW,
      PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL);
  private static final AuthGrantDto RUN = new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.RUN,
      PocketHiveResourceTypes.FOLDER, "demo");

  @Test void grantOrderDoesNotChangeTheExpectedSet() throws Exception {
    check(List.of(RUN, VIEW), "runner", true, false);
  }
  @Test void additionalPrivilegesFailTheFixtureCheck() throws Exception {
    check(List.of(VIEW, RUN, new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.ALL,
        PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)), "runner", true, true);
  }
  @Test void wrongIdentityOrInactiveActorFailsBeforeTestActions() throws Exception {
    check(List.of(VIEW, RUN), "other", true, true);
    check(List.of(VIEW, RUN), "runner", false, true);
  }

  private void check(List<AuthGrantDto> grants, String username, boolean active, boolean rejected) throws Exception {
    var profile = new AuthenticatedUserDto(UUID.randomUUID(), username, "Runner", active, AuthProvider.DEV, grants);
    try (var ingress = new ScriptedIngress()) {
      ingress.reply("POST", "/auth-service/api/auth/dev/login", 200,
          new SessionResponseDto("test-token", "Bearer", Instant.now().plusSeconds(60), profile));
      ingress.reply("GET", "/auth-service/api/auth/me", 200, profile);
      try (var run = ApiRun.open(new ApiTarget(ingress.origin(), "runner", Duration.ofSeconds(1), reports), "actor")) {
        if (rejected) assertThrows(AssertionError.class, () -> ActorAssertions.requireGrants(run, "runner", List.of(VIEW, RUN)));
        else ActorAssertions.requireGrants(run, "runner", List.of(VIEW, RUN));
      }
    }
  }
}
