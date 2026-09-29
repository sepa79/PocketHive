package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.support.OperationFixtures.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.lifecycle.*;
import java.time.Duration;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SwarmApiTest {
  @Test void stateReadHonorsTheRemainingObservationBudget() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(3))) {
      ingress.replyAfter("GET", "/orchestrator/api/swarms/" + SWARM, 200, Map.of(), Duration.ofMillis(600));
      assertThrows(HttpTimeoutException.class,
          () -> new SwarmApi(http, "").state(SWARM, Duration.ofMillis(100)));
    }
  }

  @ParameterizedTest
  @EnumSource(value = OperationType.class, names = {"CREATE", "START", "STOP", "REMOVE"})
  void rejectsAReceiptForADifferentSubmittedKey(OperationType type) throws Exception {
    var submitted = receipt(); var unrelated = receipt();
    String action = switch (type) {
      case CREATE -> "/create"; case START -> "/start"; case STOP -> "/stop"; case REMOVE -> "/remove";
      default -> throw new AssertionError(type);
    };
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.replyWith("POST", "/orchestrator/api/swarms/" + SWARM + action, 202, body -> {
        assertEquals(submitted.idempotencyKey(), body.required("idempotencyKey").textValue());
        return unrelated;
      });
      var api = new SwarmApi(http, "");
      var request = new ControlRequest(submitted.idempotencyKey());
      var failure = assertThrows(ControlReceiptMismatchException.class, () -> {
        switch (type) {
          case CREATE -> api.create(SWARM, createRequest(submitted));
          case START -> api.start(SWARM, request);
          case STOP -> api.stop(SWARM, request);
          case REMOVE -> api.remove(SWARM, request);
          default -> throw new AssertionError(type);
        }
      });
      assertEquals(unrelated, failure.receipt());
      assertTrue(failure.getMessage().contains(submitted.idempotencyKey()));
    }
  }
}
