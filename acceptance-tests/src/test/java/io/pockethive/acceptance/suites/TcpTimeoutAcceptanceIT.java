package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.config.TcpTimeoutTarget;
import io.pockethive.swarm.model.BeeRoles;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: compare successful delayed TCP work with the explicit shorter-timeout error case.
 * Must not: mutate the shared mock, connect to TCP directly or invent an error WorkItem.
 * Contract: docs/architecture/acceptance-tests.md#network-acceptance-extension-nw-2nw-3nw-5.
 */
class TcpTimeoutAcceptanceIT {
  @Test @Tag("tcp-delayed") void delayedResponseSucceedsWithSufficientReadTimeout() throws Exception {
    var target = TargetLoader.loadTcpTimeout(TargetLoader.selectedFile());
    try (var run = LiveRun.open("tcp-delayed", target.lifecycle()); var swarm = run.newSwarm()) {
      var mapping = requireSlowMapping(run, target);
      int readTimeout = processorConfig(run).required("tcpTransport").required("readTimeoutMs").intValue();
      assertTrue(readTimeout > mapping.required("fixedDelayMs").intValue());
      swarm.create(run.createRequest());
      String processor = HttpWorkAssertions.processorInstance(run.swarms.state(swarm.id()));
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture());
        swarm.start();
        for (var item : tap.awaitSamples(run.target.fixture().samples())) {
          var result = TcpWorkAssertions.requireSuccessfulResponse(item, swarm.id(), processor,
              run.target.fixture().expectedResponse());
          assertEquals("tcp", result.request().scheme());
          assertTrue(result.metrics().durationMs() >= mapping.required("fixedDelayMs").intValue());
        }
      }
      requireRuntimeConfig(run, swarm, readTimeout);
      swarm.stop();
      swarm.remove();
    }
  }

  @Test @Tag("tcp-timeout") void delayedResponseProducesRuntimeErrorWithoutPublishingAResult() throws Exception {
    var target = TargetLoader.loadTcpTimeout(TargetLoader.selectedFile());
    try (var run = LiveRun.open("tcp-timeout", target.lifecycle()); var swarm = run.newSwarm()) {
      var mapping = requireSlowMapping(run, target);
      int readTimeout = processorConfig(run).required("tcpTransport").required("readTimeoutMs").intValue();
      assertTrue(readTimeout > 0 && readTimeout < mapping.required("fixedDelayMs").intValue());
      swarm.create(run.createRequest());
      String processor = HttpWorkAssertions.processorInstance(run.swarms.state(swarm.id()));
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture());
        swarm.start();
        run.evidence.record("processor-error", RuntimeErrorObservations.awaitProcessorError(run, swarm, processor));
        tap.requireEmptyFor(target.quietWindow());
      }
      requireRuntimeConfig(run, swarm, readTimeout);
      swarm.stop();
      swarm.remove();
    }
  }

  private static JsonNode requireSlowMapping(LiveRun run, TcpTimeoutTarget target) throws Exception {
    var mapping = run.tcpMock(target.mockUsername(), target.mockPassword()).requireMapping(target.mappingId());
    run.evidence.record("mock-mapping", mapping);
    assertEquals(true, mapping.required("enabled").booleanValue());
    assertEquals(5000, mapping.required("fixedDelayMs").intValue());
    assertEquals("SLOW_.*", mapping.required("requestPattern").textValue());
    assertEquals(run.target.fixture().expectedResponse(), mapping.required("responseTemplate").textValue());
    return mapping;
  }
  private static JsonNode processorConfig(LiveRun run) {
    var bees = run.scenario.required("template").required("bees");
    return java.util.stream.StreamSupport.stream(bees.spliterator(), false)
        .filter(bee -> BeeRoles.PROCESSOR.equals(bee.required("role").textValue())).findFirst().orElseThrow().required("config");
  }
  private static void requireRuntimeConfig(LiveRun run, io.pockethive.acceptance.resources.SwarmResource swarm,
                                           int expectedReadTimeout) throws Exception {
    var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm,
        Set.of(BeeRoles.GENERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR));
    var processor = workers.stream().filter(worker -> BeeRoles.PROCESSOR.equals(worker.required("role").textValue()))
        .findFirst().orElseThrow();
    var sut = run.scenarios.requireBundleSut(run.target.fixture().templateId(), run.target.fixture().sutId());
    run.evidence.record("sut", sut);
    assertEquals(sut.endpoints().get("default").baseUrl(), processor.required("config").required("baseUrl").textValue());
    var transport = processor.required("config").required("tcpTransport");
    assertEquals(expectedReadTimeout, transport.required("readTimeoutMs").intValue());
    assertEquals(0, transport.required("maxRetries").intValue());
  }
}
