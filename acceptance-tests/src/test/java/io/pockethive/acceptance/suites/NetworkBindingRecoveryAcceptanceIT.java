package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import io.pockethive.swarm.model.ResolvedSutEnvironment;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify proxy apply, rejected candidate recovery, traffic and explicit clear.
 * Must not: resolve addresses, inspect native files or own swarm/binding cleanup.
 * Contract: docs/architecture/acceptance-tests.md#binding-recovery-acceptance-nw-4.
 */
@Tag("network-binding-recovery")
class NetworkBindingRecoveryAcceptanceIT {
  @Test void rejectedCandidatePreservesWorkingBindingUntilExplicitClear() throws Exception {
    var target = TargetLoader.loadBindingRecovery(TargetLoader.selectedFile());
    var proxy = target.proxy();
    try (var run = LiveRun.open("network-binding-recovery", proxy.lifecycle()); var swarm = run.newSwarm()) {
      var fixture = run.target.fixture();
      var sut = run.scenarios.requireBundleSut(fixture.templateId(), fixture.sutId());
      run.evidence.record("sut", sut);
      run.evidence.record("binding-before-create", run.networkBindings.requireAbsent(swarm.id()));
      swarm.create(run.createRequest(null, NetworkMode.PROXIED, proxy.networkProfileId()));
      var endpoint = ProxyAssertions.requireBinding(run, swarm.id(), proxy, sut, "http");
      var previous = run.networkBindings.requireBinding(swarm.id());
      run.evidence.record("binding-before-candidate", previous);
      String processor = HttpWorkAssertions.processorInstance(run.swarms.state(swarm.id()));
      Set<String> before = observeTraffic(run, swarm, endpoint, processor);
      swarm.stop();

      // Deliberate malformed input, not a production address resolver. All other values come from the owner.
      var invalidEndpoint = new ResolvedSutEndpoint(endpoint.endpointId(), endpoint.kind(),
          endpoint.clientBaseUrl(), "invalid:-1", endpoint.upstreamAuthority());
      var candidate = new NetworkBindingRequest(previous.sutId(), previous.networkMode(),
          previous.networkProfileId(), run.target.api().username(), "NW-4 invalid candidate",
          new ResolvedSutEnvironment(sut.id(), sut.name(), sut.type(), Map.of(endpoint.endpointId(), invalidEndpoint)));
      run.evidence.record("invalid-candidate", candidate);
      long began = System.nanoTime();
      var rejection = run.networkBindings.bind(swarm.id(), candidate);
      var elapsed = Duration.ofNanos(System.nanoTime() - began);
      run.evidence.record("candidate-response", rejection);
      run.evidence.record("candidate-timing", Map.of("elapsedMs", elapsed.toMillis(),
          "minimumMs", target.minimumRejectionDuration().toMillis()));
      var retained = run.networkBindings.requireBinding(swarm.id());
      run.evidence.record("binding-after-candidate", retained);
      BindingRecoveryAssertions.requireRejectedAndPreserved(previous, rejection, elapsed,
          target.minimumRejectionDuration(), retained);

      // A separate tap cannot reuse the first observation's retained samples.
      Set<String> after = observeTraffic(run, swarm, endpoint, processor);
      assertTrue(Collections.disjoint(before, after), "Post-rejection traffic must contain different WorkItems");
      swarm.stop();
      var cleared = run.networkBindings.clear(swarm.id(),
          new NetworkBindingClearRequest(previous.sutId(), run.target.api().username(), "NW-4 explicit clear"));
      run.evidence.record("binding-clear-response", cleared);
      cleared.expect(200);
      run.evidence.record("binding-after-clear", run.networkBindings.requireAbsent(swarm.id()));
      swarm.remove();
      run.evidence.record("binding-after-remove", run.networkBindings.requireAbsent(swarm.id()));
    }
  }

  private static Set<String> observeTraffic(LiveRun run, SwarmResource swarm,
      ResolvedSutEndpoint endpoint, String processor) throws Exception {
    try (var tap = run.newTap()) {
      tap.open(swarm.id(), run.target.fixture().tap());
      swarm.start();
      var samples = tap.awaitSamples(run.target.fixture().samples());
      for (var sample : samples) {
        var result = HttpWorkAssertions.requireSuccessfulResponse(sample, swarm.id(), processor,
            run.target.fixture().expectedResponse());
        assertEquals(endpoint.clientBaseUrl(), result.request().baseUrl());
        assertEquals(endpoint.clientAuthority(), URI.create(result.request().url()).getAuthority());
      }
      return samples.stream().map(io.pockethive.work.api.WorkItem::messageId).collect(Collectors.toSet());
    }
  }
}
