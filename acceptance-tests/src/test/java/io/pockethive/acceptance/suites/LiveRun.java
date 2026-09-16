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
import java.nio.file.Path;
import java.util.UUID;

/**
 * Responsibility: compose one live test's explicitly configured API collaborators.
 * Must not: retain global state or implement lifecycle, capture or cleanup.
 * Contract: RESP-ACCEPTANCE-RUN — docs/architecture/acceptance-tests.md#resp-acceptance-run.
 */
final class LiveRun implements AutoCloseable {
  final AcceptanceTarget target;
  final RunEvidence evidence;
  final SwarmApi swarms;
  private final PocketHiveHttp http;
  private final String token;
  private LiveRun(AcceptanceTarget target, RunEvidence evidence, PocketHiveHttp http, String token) {
    this.target = target; this.evidence = evidence; this.http = http; this.token = token;
    swarms = new SwarmApi(http, token);
  }
  static LiveRun open(String testName) throws Exception {
    String selected = System.getProperty("acceptance.target");
    if (selected == null || selected.isBlank()) throw new IllegalArgumentException("Explicit acceptance.target is required");
    var target = TargetLoader.load(Path.of(selected));
    var evidence = new RunEvidence(target.evidenceDirectory(), testName);
    var http = new PocketHiveHttp(target.ingress(), target.limits().request());
    try {
      String token = new AuthApi(http).devLogin(target.username());
      evidence.record("fixture", new ScenarioApi(http, token).requireScenario(target.fixture().templateId()));
      System.out.println("Acceptance evidence: " + evidence.directory());
      return new LiveRun(target, evidence, http, token);
    } catch (Exception | AssertionError failure) {
      try (http; evidence) { throw failure; }
    }
  }
  SwarmResource newSwarm() {
    return new SwarmResource("acceptance-" + UUID.randomUUID(), swarms,
        new OperationAwaiter(swarms, target.limits(), evidence), target.limits());
  }
  TapResource newTap() { return new TapResource(new DebugTapApi(http, token), target.limits(), evidence); }
  SwarmCreateRequest createRequest() {
    return SwarmCreateRequest.of(target.fixture().templateId(), UUID.randomUUID().toString(), false,
        target.fixture().sutId(), null, NetworkMode.DIRECT, null);
  }
  @Override public void close() throws IOException {
    try (http; evidence) { /* Evidence closes after the test resources; HTTP closes even on report failure. */ }
  }
}
