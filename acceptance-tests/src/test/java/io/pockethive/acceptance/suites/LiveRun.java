package io.pockethive.acceptance.suites;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.capture.DebugTapApi;
import io.pockethive.acceptance.capture.TapResource;
import io.pockethive.acceptance.config.AcceptanceTarget;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import java.io.IOException;
import java.util.UUID;

/**
 * Responsibility: compose one lifecycle test on the shared API session scope.
 * Must not: own authentication/HTTP lifetime or implement lifecycle, capture or cleanup.
 * Contract: RESP-ACCEPTANCE-RUN — docs/architecture/acceptance-tests.md#resp-acceptance-run.
 */
final class LiveRun implements AutoCloseable {
  final AcceptanceTarget target;
  final JsonNode scenario;
  final RunEvidence evidence;
  final SwarmApi swarms;
  final ScenarioApi scenarios;
  final NetworkBindingApi networkBindings;
  final SwarmJournalApi journal;
  private final ApiRun api;
  private LiveRun(AcceptanceTarget target, ApiRun api, JsonNode scenario) {
    this.target = target;
    this.scenario = scenario;
    this.api = api;
    evidence = api.evidence;
    swarms = new SwarmApi(api.http, api.token);
    scenarios = new ScenarioApi(api.http, api.token);
    networkBindings = new NetworkBindingApi(api.http, api.token);
    journal = new SwarmJournalApi(api.http, api.token);
  }
  static LiveRun open(String testName) throws Exception {
    return open(testName, TargetLoader.load(TargetLoader.selectedFile()));
  }
  static LiveRun open(String testName, AcceptanceTarget target) throws Exception {
    var api = ApiRun.open(target.api(), testName);
    try {
      var scenario = new ScenarioApi(api.http, api.token).requireScenario(target.fixture().templateId());
      api.evidence.record("fixture", scenario);
      return new LiveRun(target, api, scenario);
    } catch (Exception | Error failure) {
      try (api) { throw failure; }
    }
  }
  SwarmResource newSwarm() {
    return new SwarmResource("acceptance-" + UUID.randomUUID(), swarms,
        new OperationAwaiter(swarms, target.limits().operations(), evidence), target.limits().operations());
  }
  RedisCommanderApi redis(String connectionId) { return new RedisCommanderApi(api.http, connectionId); }
  TcpMockApi tcpMock(String username, String password) { return new TcpMockApi(api.http, username, password); }
  TapResource newTap() { return new TapResource(new DebugTapApi(api.http, api.token), target.limits(), evidence); }
  SwarmCreateRequest createRequest() { return createRequest(null); }
  SwarmCreateRequest createRequest(String variablesProfileId) {
    return createRequest(variablesProfileId, NetworkMode.DIRECT, null);
  }
  SwarmCreateRequest createRequest(String variablesProfileId, NetworkMode mode, String networkProfileId) {
    return SwarmCreateRequest.of(target.fixture().templateId(), UUID.randomUUID().toString(), false,
        target.fixture().sutId(), variablesProfileId, mode, networkProfileId);
  }
  @Override public void close() throws IOException {
    api.close();
  }
}
