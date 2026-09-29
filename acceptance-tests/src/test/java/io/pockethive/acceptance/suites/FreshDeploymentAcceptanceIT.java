package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.assertTrue;
import io.pockethive.acceptance.api.SwarmApi;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.auth.contract.*;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify that a declared fresh deployment exposes no implicitly created swarms.
 * Must not: infer freshness, reset an environment or create/delete swarms to make the assertion pass.
 * Contract: docs/architecture/acceptance-tests.md#fresh-deployment-smoke-sm-2.
 */
@Tag("fresh-deployment")
class FreshDeploymentAcceptanceIT {
  @Test void freshDeploymentHasNoImplicitSwarm() throws Exception {
    var target = TargetLoader.loadFreshDeployment(TargetLoader.selectedFile());
    try (var run = ApiRun.open(target.api(), "fresh-deployment")) {
      run.evidence.record("declared-fresh-target", target);
      ActorAssertions.requireGrants(run, target.api().username(), List.of(new AuthGrantDto(AuthProduct.POCKETHIVE,
          PocketHivePermissionIds.ALL, PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)));
      var swarms = new SwarmApi(run.http, run.token).list();
      run.evidence.record("initial-swarms", swarms);
      assertTrue(swarms.isEmpty(), "Fresh deployment must not create implicit swarms: " + swarms);
    }
  }
}
