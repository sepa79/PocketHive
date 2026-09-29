package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.suites.CatalogueAssertions.*;
import io.pockethive.acceptance.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: prove a newly provisioned RUN actor can run only its exact bundle.
 * Must not: calculate grants or implement actor/swarm cleanup.
 * Contract: docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
@Tag("auth-provisioned")
class BundleAuthorizationAcceptanceIT {
  @Test void exactBundleGrantControlsProfileCatalogueAndCreate() throws Exception {
    try (var fixture = AuthFixture.open("bundle-admin"); var user = fixture.newUser()) {
      var grants = fixture.bundleRunnerGrants();
      user.provision(grants);
      try (var actor = fixture.login(user, "bundle-runner", grants)) {
        var runnable = catalogue(actor);
        assertEquals(1, runnable.size());
        assertEquals(fixture.target.bundle(), entry(runnable, fixture.target.scenarioId()).required("bundlePath").textValue());
        var requester = new SwarmApi(actor.http, actor.token);
        var observer = new SwarmApi(fixture.admin.http, fixture.admin.token);
        try (var allowed = fixture.newSwarm(fixture.admin)) {
          observer.requireAbsent(allowed.id());
          allowed.create(fixture.request(fixture.target.scenarioId()), requester);
          assertEquals(allowed.runId(), observer.state(allowed.id()).runId());
          allowed.remove();
        }
        for (String deniedScenario : java.util.List.of(fixture.target.siblingScenarioId(), fixture.target.outsideScenarioId())) {
          try (var denied = fixture.newSwarm(fixture.admin)) {
            observer.requireAbsent(denied.id());
            var failure = assertThrows(ApiException.class, () -> denied.create(fixture.request(deniedScenario), requester));
            actor.evidence.record("create-denied-" + deniedScenario, failure.response());
            failure.response().expect(403);
            observer.requireAbsent(denied.id());
          }
        }
      }
    }
  }
}
