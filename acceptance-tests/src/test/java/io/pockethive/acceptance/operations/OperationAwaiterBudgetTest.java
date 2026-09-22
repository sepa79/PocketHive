package io.pockethive.acceptance.operations;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.support.OperationFixtures.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.OperationLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.lifecycle.*;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationAwaiterBudgetTest {
  @TempDir Path reports;
  @Test void receiptBudgetBoundsAnOtherwiseAllowedSlowResponse() throws Exception { slowResponse(200, 2000); }
  @Test void configuredBudgetBoundsALongerServerOffer() throws Exception { slowResponse(2000, 200); }

  private void slowResponse(long offeredMillis, long configuredMillis) throws Exception {
    var source = receipt();
    var receipt = new ControlResponse(source.correlationId(), source.idempotencyKey(), source.operationUrl(),
        source.outcomeTopic(), offeredMillis);
    var limits = new OperationLimits(Duration.ofSeconds(2), Duration.ofMillis(configuredMillis), Duration.ofMillis(10));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "budget")) {
      ingress.replyAfter("GET", "/orchestrator" + receipt.operationUrl(), 200,
          succeeded(receipt, OperationType.START), Duration.ofMillis(600));
      var waiter = new OperationAwaiter(new SwarmApi(http, ""), limits, evidence);
      assertThrows(HttpTimeoutException.class, () -> waiter.terminal(SWARM, OperationType.START, receipt));
    }
  }
  @Test void pendingReadAndPollingDoNotRenewTheNextRequestBudget() throws Exception {
    var receipt = receipt();
    var limits = new OperationLimits(Duration.ofSeconds(2), Duration.ofMillis(600), Duration.ofMillis(150));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "remaining")) {
      ingress.replyAfter("GET", "/orchestrator" + receipt.operationUrl(), 200,
          operation(receipt, OperationType.START, OperationState.DISPATCHED, Map.of()), Duration.ofMillis(200))
          .replyAfter("GET", "/orchestrator" + receipt.operationUrl(), 200,
              succeeded(receipt, OperationType.START), Duration.ofMillis(450));
      var waiter = new OperationAwaiter(new SwarmApi(http, ""), limits, evidence);
      assertThrows(HttpTimeoutException.class, () -> waiter.terminal(SWARM, OperationType.START, receipt));
    }
  }
  @Test void longPollIntervalEndsAtTheDeadlineWithoutAnotherRequest() throws Exception {
    var receipt = receipt();
    // Allow a cold HTTP exchange under build load before exercising the longer-than-budget poll.
    var limits = new OperationLimits(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(4));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "poll-budget")) {
      ingress.reply("GET", "/orchestrator" + receipt.operationUrl(), 200,
          operation(receipt, OperationType.START, OperationState.DISPATCHED, Map.of()));
      var waiter = new OperationAwaiter(new SwarmApi(http, ""), limits, evidence);
      var error = assertThrows(AssertionError.class, () -> waiter.terminal(SWARM, OperationType.START, receipt));
      assertTrue(error.getMessage().contains(receipt.correlationId()));
      assertTrue(error.getMessage().contains("timed out"));
      var observation = new ObjectMapper().readTree(
          evidence.directory().resolve("operation-" + receipt.correlationId() + ".json").toFile());
      assertEquals(OperationState.DISPATCHED.name(), observation.required("state").textValue());
    }
  }
}
