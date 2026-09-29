package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.work.api.WorkItem;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify actual TCPS results via the selected managed proxy and its removal.
 * Must not: resolve endpoints, instantiate TCP clients or clear bindings directly.
 * Contract: docs/architecture/acceptance-tests.md#network-acceptance-extension-nw-2nw-3nw-5.
 */
@Tag("tcps-proxy")
class TcpsProxyAcceptanceIT {
  @Test void processesTcpsThroughProxyAndRemovesBinding() throws Exception {
    var target = TargetLoader.loadProxy(TargetLoader.selectedFile());
    try (var run = LiveRun.open("tcps-proxy", target.lifecycle()); var swarm = run.newSwarm()) {
      var fixture = run.target.fixture();
      var sut = run.scenarios.requireBundleSut(fixture.templateId(), fixture.sutId());
      run.evidence.record("sut", sut);
      run.evidence.record("binding-before-create", run.networkBindings.requireAbsent(swarm.id()));
      swarm.create(run.createRequest(null, NetworkMode.PROXIED, target.networkProfileId()));
      var bound = ProxyAssertions.requireBinding(run, swarm.id(), target, sut, "tcps");
      List<WorkItem> samples;
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), fixture.tap());
        swarm.start();
        samples = tap.awaitSamples(fixture.samples());
      }
      var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm,
          Set.of(BeeRoles.GENERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR));
      var processor = workers.stream().filter(worker -> BeeRoles.PROCESSOR.equals(worker.required("role").textValue()))
          .findFirst().orElseThrow();
      assertEquals(bound.clientBaseUrl(), processor.required("config").required("baseUrl").textValue());
      assertEquals(BooleanNode.FALSE, processor.required("config").required("tcpTransport").required("sslVerify"));
      for (var sample : samples) {
        var result = TcpWorkAssertions.requireSuccessfulResponse(sample, swarm.id(),
            processor.required("instance").textValue(), fixture.expectedResponse());
        assertEquals("tcps", result.request().scheme());
        assertEquals(bound.clientBaseUrl(), result.request().configuredTarget());
        assertNotNull(result.request().endpoint());
        assertEquals("tcps", URI.create(result.request().endpoint()).getScheme());
        assertEquals(bound.clientAuthority(), URI.create(result.request().endpoint()).getAuthority());
      }
      swarm.stop();
      swarm.remove();
      run.evidence.record("binding-after-remove", run.networkBindings.requireAbsent(swarm.id()));
    }
  }
}
