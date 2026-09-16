package io.pockethive.acceptance.operations;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.support.OperationFixtures.*;
import io.pockethive.acceptance.api.PocketHiveHttp;
import io.pockethive.acceptance.api.SwarmApi;
import io.pockethive.acceptance.config.WaitLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.lifecycle.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationAwaiterTest {
  @TempDir Path reports;
  private final WaitLimits limits = new WaitLimits(Duration.ofSeconds(1), Duration.ofSeconds(2),
      Duration.ofSeconds(1), Duration.ofMillis(1));

  @Test void waitsForTheCanonicalTerminalResult() throws Exception {
    var receipt = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "operations")) {
      ingress.reply("GET", "/orchestrator" + receipt.operationUrl(), 200, operation(receipt, OperationType.START, OperationState.DISPATCHED, Map.of()));
      ingress.reply("GET", "/orchestrator" + receipt.operationUrl(), 200, succeeded(receipt, OperationType.START));
      var waiter = new OperationAwaiter(new SwarmApi(http, ""), limits, evidence);
      assertEquals(OperationState.SUCCEEDED, waiter.terminal(SWARM, OperationType.START, receipt).state());
    }
  }
  @Test void terminalFailureDoesNotBecomeARepeatedReadUntilTimeout() throws Exception {
    var receipt = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "operations")) {
      ingress.reply("GET", "/orchestrator" + receipt.operationUrl(), 200,
          operation(receipt, OperationType.START, OperationState.FAILED, Map.of("reason", "rejected setup")));
      var waiter = new OperationAwaiter(new SwarmApi(http, ""), limits, evidence);
      var result = waiter.terminal(SWARM, OperationType.START, receipt);
      var error = assertThrows(AssertionError.class, () -> OperationAwaiter.requireSucceeded(result));
      assertTrue(error.getMessage().contains(receipt.correlationId()));
      assertTrue(error.getMessage().contains("FAILED"));
    }
  }
  @Test void rejectsDifferentOperationIdentity() throws Exception {
    var expected = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "operations")) {
      ingress.reply("GET", "/orchestrator" + expected.operationUrl(), 200, succeeded(receipt(), OperationType.START));
      var waiter = new OperationAwaiter(new SwarmApi(http, ""), limits, evidence);
      assertThrows(AssertionError.class, () -> waiter.terminal(SWARM, OperationType.START, expected));
    }
  }
}
