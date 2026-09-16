package io.pockethive.acceptance.suites;

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
  final RunEvidence evidence;
  final SwarmApi swarms;
  private final ApiRun api;
  private LiveRun(AcceptanceTarget target, ApiRun api) {
    this.target = target;
    this.api = api;
    evidence = api.evidence;
    swarms = new SwarmApi(api.http, api.token);
  }
  static LiveRun open(String testName) throws Exception {
    var target = TargetLoader.load(TargetLoader.selectedFile());
    var api = ApiRun.open(target.api(), testName);
    try {
      api.evidence.record("fixture", new ScenarioApi(api.http, api.token).requireScenario(target.fixture().templateId()));
      return new LiveRun(target, api);
    } catch (Exception | Error failure) {
      try (api) { throw failure; }
    }
  }
  SwarmResource newSwarm() {
    return new SwarmResource("acceptance-" + UUID.randomUUID(), swarms,
        new OperationAwaiter(swarms, target.limits().operations(), evidence), target.limits().operations());
  }
  TapResource newTap() { return new TapResource(new DebugTapApi(api.http, api.token), target.limits(), evidence); }
  SwarmCreateRequest createRequest() {
    return SwarmCreateRequest.of(target.fixture().templateId(), UUID.randomUUID().toString(), false,
        target.fixture().sutId(), null, NetworkMode.DIRECT, null);
  }
  @Override public void close() throws IOException {
    api.close();
  }
}
