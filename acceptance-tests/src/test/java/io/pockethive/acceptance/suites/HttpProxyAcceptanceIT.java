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
 * Responsibility: verify HTTP/HTTPS through the selected proxy and removal of the owned binding.
 * Must not: compute proxy addresses, write bindings or implement lifecycle/cleanup.
 * Contract: docs/architecture/acceptance-tests.md#network-acceptance-extension-nw-2nw-3nw-5.
 */
class HttpProxyAcceptanceIT {
  @Test @Tag("http-proxy") void processesHttpThroughSelectedProxyAndRemovesBinding() throws Exception {
    verifyProxy("http");
  }
  @Test @Tag("https-proxy") void processesHttpsThroughSelectedProxyAndRemovesBinding() throws Exception {
    verifyProxy("https");
  }
  private void verifyProxy(String scheme) throws Exception {
    var target = TargetLoader.loadProxy(TargetLoader.selectedFile());
    try (var run = LiveRun.open(scheme + "-proxy", target.lifecycle()); var swarm = run.newSwarm()) {
      var fixture = run.target.fixture();
      var sut = run.scenarios.requireBundleSut(fixture.templateId(), fixture.sutId());
      run.evidence.record("sut", sut);
      run.evidence.record("binding-before-create", run.networkBindings.requireAbsent(swarm.id()));
      swarm.create(run.createRequest(null, NetworkMode.PROXIED, target.networkProfileId()));
      var boundEndpoint = ProxyAssertions.requireBinding(run, swarm.id(), target, sut, scheme);
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
      assertEquals(boundEndpoint.clientBaseUrl(), processor.required("config").required("baseUrl").textValue(),
          "Processor runtime must use the selected binding's client URL");
      if ("https".equals(scheme)) {
        assertEquals(BooleanNode.FALSE, processor.required("config").required("sslVerify"),
            "Local HTTPS fixture explicitly disables verification of the stub certificate");
      }
      for (var sample : samples) {
        var result = HttpWorkAssertions.requireSuccessfulResponse(sample, swarm.id(),
            processor.required("instance").textValue(), fixture.expectedResponse());
        assertEquals(boundEndpoint.clientBaseUrl(), result.request().baseUrl());
        assertEquals(scheme, result.request().scheme());
        assertNotNull(result.request().url());
        assertEquals(scheme, URI.create(result.request().url()).getScheme());
        assertEquals(boundEndpoint.clientAuthority(), URI.create(result.request().url()).getAuthority(),
            "Successful HTTP request must address the selected proxy");
      }
      swarm.stop();
      swarm.remove();
      run.evidence.record("binding-after-remove", run.networkBindings.requireAbsent(swarm.id()));
    }
  }
}
