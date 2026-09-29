package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.suites.CatalogueAssertions.*;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.*;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.acceptance.resources.*;
import io.pockethive.auth.contract.*;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import java.util.List;
import java.util.UUID;

/**
 * Responsibility: compose explicit auth fixtures with existing HTTP, user and swarm resource owners.
 * Must not: implement grant decisions, lifecycle convergence or an alternate cleanup path.
 * Contract: docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
final class AuthFixture implements AutoCloseable {
  final ProvisionedAuthTarget target;
  final ApiRun admin;
  private AuthFixture(ProvisionedAuthTarget target, ApiRun admin) { this.target = target; this.admin = admin; }
  static AuthFixture open(String name) throws Exception {
    return open(name, TargetLoader.loadProvisionedAuth(TargetLoader.selectedFile()));
  }
  static AuthFixture open(String name, ProvisionedAuthTarget target) throws Exception {
    var admin = ApiRun.open(target.api(), name);
    try {
      ActorAssertions.requireGrants(admin, target.api().username(), List.of(grant(PocketHivePermissionIds.ALL,
          PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)));
      var catalogue = catalogue(admin);
      var allowed = entry(catalogue, target.scenarioId());
      assertEquals(target.bundle(), allowed.required("bundlePath").textValue());
      assertTrue(inFolder(allowed, target.folder()));
      assertTrue(inFolder(entry(catalogue, target.siblingScenarioId()), target.folder()));
      assertFalse(inFolder(entry(catalogue, target.outsideScenarioId()), target.folder()));
      return new AuthFixture(target, admin);
    } catch (Exception | Error failure) { try (admin) { throw failure; } }
  }
  AuthUserResource newUser() {
    UUID id = UUID.randomUUID();
    return new AuthUserResource(id, "acceptance-" + id, new AuthAdminApi(admin.http, admin.token),
        new AuthApi(admin.http), admin.evidence);
  }
  ApiRun login(AuthUserResource user, String name, List<AuthGrantDto> grants) throws Exception {
    var api = target.api();
    var run = ApiRun.open(new ApiTarget(api.ingress(), user.username(), api.requestTimeout(), api.evidenceDirectory()), name);
    try {
      ActorAssertions.requireGrants(run, user.username(), grants);
      assertEquals(user.id(), new AuthApi(run.http).profile(run.token).id());
      return run;
    } catch (Exception | Error failure) { try (run) { throw failure; } }
  }
  SwarmResource newSwarm(ApiRun operator) {
    var api = new SwarmApi(operator.http, operator.token);
    return new SwarmResource("acceptance-auth-" + UUID.randomUUID(), api,
        new OperationAwaiter(api, target.limits(), operator.evidence), target.limits());
  }
  SwarmCreateRequest request(String scenario) {
    return SwarmCreateRequest.of(scenario, UUID.randomUUID().toString(), false, target.sutId(), null, NetworkMode.DIRECT, null);
  }
  List<AuthGrantDto> bundleRunnerGrants() {
    return List.of(view(), grant(PocketHivePermissionIds.RUN, PocketHiveResourceTypes.BUNDLE, target.bundle()));
  }
  List<AuthGrantDto> folderAdminGrants() {
    return List.of(view(), grant(PocketHivePermissionIds.ALL, PocketHiveResourceTypes.FOLDER, target.folder()));
  }
  List<AuthGrantDto> viewerGrants() { return List.of(view()); }
  private static AuthGrantDto view() {
    return grant(PocketHivePermissionIds.VIEW, PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL);
  }
  private static AuthGrantDto grant(String permission, String type, String selector) {
    return new AuthGrantDto(AuthProduct.POCKETHIVE, permission, type, selector);
  }
  @Override public void close() throws Exception { admin.close(); }
}
