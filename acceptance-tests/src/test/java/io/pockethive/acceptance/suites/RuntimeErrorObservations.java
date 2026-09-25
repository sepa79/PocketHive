package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.control.AlertMessage;
import io.pockethive.swarm.model.BeeRoles;

/**
 * Responsibility: await a runtime error in the owned processor's public journal projection.
 * Must not: consume/merge CP events, synthesize causes or settle lifecycle operations.
 * Contract: docs/architecture/acceptance-tests.md#network-acceptance-extension-nw-2nw-3nw-5.
 */
final class RuntimeErrorObservations {
  private RuntimeErrorObservations() {}
  static JsonNode awaitProcessorError(LiveRun run, SwarmResource swarm, String processor) throws Exception {
    var deadline = new Deadline(run.target.limits().capture(), "Processor runtime error for " + swarm.id());
    while (true) {
      var entries = run.journal.read(swarm.id(), swarm.runId(), deadline.remaining());
      run.evidence.record("error-journal", entries);
      assertTrue(entries.isArray(), "Journal timeline must be an array");
      for (var entry : entries) {
        if (!AlertMessage.TYPE.equals(entry.path("type").textValue())
            || !BeeRoles.PROCESSOR.equals(entry.path("scope").path("role").textValue())) continue;
        assertEquals(swarm.id(), entry.required("swarmId").textValue());
        assertEquals(swarm.id(), entry.required("scope").required("swarmId").textValue());
        assertEquals(processor, entry.required("scope").required("instance").textValue());
        assertEquals(swarm.runId(), entry.required("raw").required("runtime").required("runId").textValue());
        var data = entry.required("data");
        assertEquals("runtime.exception", data.required("code").textValue());
        assertEquals("work", data.required("context").required("phase").textValue());
        assertFalse(data.required("context").required("messageId").asText().isBlank());
        assertEquals("java.lang.IllegalStateException", data.required("errorType").textValue());
        assertEquals("Processor request failed", data.required("message").textValue());
        return entry;
      }
      deadline.pause(run.target.limits().poll());
    }
  }
}
