package io.pockethive.acceptance.suites;

import io.pockethive.acceptance.api.ApiSurface;
import io.pockethive.acceptance.config.TargetLoader;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Responsibility: assert authenticated read access and anonymous denial on the explicit public API matrix.
 * Must not: provision actors, mutate resources or infer authorization rules.
 * Contract: docs/ci/acceptance-coverage.md — AU-1/AU-2; docs/architecture/acceptance-tests.md#auth-read-acceptance-slice.
 */
@Tag("auth-read")
class AuthReadAcceptanceIT {
  private static final String JSON = "application/json";
  private static final String TEXT = "text/plain";
  @ParameterizedTest(name = "{0}: authenticated 200, anonymous 401")
  @MethodSource("readEndpoints")
  void requiresAuthentication(String name, ApiSurface surface, String servicePath, String accept) throws Exception {
    var target = TargetLoader.loadScenario(TargetLoader.selectedFile());
    String scenarioId = ApiSurface.pathSegment(target.scenarioId());
    String path = surface.publicPath(servicePath.formatted(scenarioId));
    try (var run = ApiRun.open(target.api(), "auth-read-" + name)) {
      var anonymous = run.http.request("GET", path, null, "", accept);
      run.evidence.record("anonymous-response", anonymous);
      anonymous.expect(401);
      var authenticated = run.http.request("GET", path, null, run.token, accept);
      run.evidence.record("authenticated-response", authenticated);
      authenticated.expect(200);
    }
  }

  static Stream<Arguments> readEndpoints() {
    return Stream.of(
        Arguments.of("templates", ApiSurface.SCENARIO_MANAGER, "/api/templates", JSON),
        Arguments.of("swarms", ApiSurface.ORCHESTRATOR, "/api/swarms", JSON),
        Arguments.of("scenarios", ApiSurface.SCENARIO_MANAGER, "/scenarios?includeDefunct=true", JSON),
        Arguments.of("scenario-detail", ApiSurface.SCENARIO_MANAGER, "/scenarios/%s", JSON),
        Arguments.of("scenario-raw", ApiSurface.SCENARIO_MANAGER, "/scenarios/%s/raw", TEXT),
        Arguments.of("capabilities", ApiSurface.SCENARIO_MANAGER, "/api/capabilities?all=true", JSON),
        Arguments.of("workspaces", ApiSurface.SCENARIO_MANAGER, "/scenarios/bundles/workspaces", JSON),
        Arguments.of("network-profiles", ApiSurface.SCENARIO_MANAGER, "/network-profiles/raw", TEXT),
        Arguments.of("sut-environments", ApiSurface.SCENARIO_MANAGER, "/sut-environments/raw", TEXT),
        Arguments.of("control-schema", ApiSurface.ORCHESTRATOR, "/api/control-plane/schema/control-events", JSON),
        Arguments.of("hive-journal", ApiSurface.ORCHESTRATOR, "/api/journal/hive/page?limit=1", JSON),
        Arguments.of("network-bindings", ApiSurface.NETWORK_PROXY_MANAGER, "/api/network/bindings", JSON),
        Arguments.of("network-proxies", ApiSurface.NETWORK_PROXY_MANAGER, "/api/network/proxies", JSON));
  }
}
