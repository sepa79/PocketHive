package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.support.OperationFixtures.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.BeeRoles;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmManagementApiTest {
  @Test void managerReceiptMustMatchTheRequestedKeyAndTarget() throws Exception {
    for (String mismatch : List.of("none", "key", "target")) {
      var receipt = receipt();
      try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
        ingress.replyWith("POST", "/orchestrator/api/swarm-managers/owned/enabled", 202, body -> {
          assertTrue(body.required("enabled").booleanValue());
          return Map.of("dispatches", List.of(Map.of("swarm", "owned", "instanceId", mismatch.equals("target") ? "other" : "controller",
              "response", receipt)));
        });
        String key = mismatch.equals("key") ? "unexpected" : receipt.idempotencyKey();
        var api = new SwarmManagementApi(http, "token");
        if (mismatch.equals("none")) assertEquals(receipt, api.managerEnabled("owned", "controller", key, true));
        else assertEquals(receipt, assertThrows(ControlReceiptMismatchException.class,
            () -> api.managerEnabled("owned", "controller", key, true)).receipt());
      }
    }
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {BeeRoles.SWARM_CONTROLLER, BeeRoles.POSTPROCESSOR})
  void componentConfigUsesTheRequestedRoleAndCanonicalReceipt(String role) throws Exception {
    var receipt = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.replyWith("POST", "/orchestrator/api/components/" + role + "/worker%20one/config", 202, body -> {
        assertEquals("owned", body.required("swarmId").textValue());
        assertTrue(body.required("patch").required("enabled").booleanValue());
        return receipt;
      });
      assertEquals(receipt, new SwarmManagementApi(http, "token").componentConfig("owned", role, "worker one",
          receipt.idempotencyKey(), Map.of("enabled", true)));
    }
  }
}
