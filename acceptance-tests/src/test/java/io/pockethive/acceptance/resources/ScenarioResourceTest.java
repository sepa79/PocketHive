package io.pockethive.acceptance.resources;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioResourceTest {
  @TempDir Path reports;
  private static final String ROOT = "/scenario-manager/scenarios";
  @Test void assertionFailureDeletesOnlyTheOwnedScenarioAndVerifiesAbsence() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "scenario")) {
      ingress.reply("GET", ROOT + "/owned", 404, Map.of()).reply("POST", ROOT, 201, Map.of("id", "owned"))
          .reply("GET", ROOT + "/owned", 200, Map.of("id", "owned"))
          .reply("DELETE", ROOT + "/owned", 204, Map.of()).reply("GET", ROOT + "/owned", 404, Map.of());
      var api = new ScenarioApi(http, "token");
      assertThrows(AssertionError.class, () -> {
        try (var resource = new ScenarioResource("owned", api, evidence)) {
          resource.create(new ObjectMapper().valueToTree(Map.of("id", "owned")), api).expect(201);
          throw new AssertionError("body failed");
        }
      });
    }
  }
  @Test void preExistingScenarioIsNeverMutated() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "collision")) {
      ingress.reply("GET", ROOT + "/owned", 200, Map.of("id", "owned"));
      var api = new ScenarioApi(http, "token");
      try (var resource = new ScenarioResource("owned", api, evidence)) {
        assertThrows(ApiException.class, () -> resource.create(new ObjectMapper().valueToTree(Map.of("id", "owned")), api));
      }
    }
  }
  @Test void successfulDeleteWithRemainingScenarioIsAFailure() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "not-deleted")) {
      ingress.reply("GET", ROOT + "/owned", 404, Map.of()).reply("POST", ROOT, 201, Map.of("id", "owned"))
          .reply("GET", ROOT + "/owned", 200, Map.of("id", "owned"))
          .reply("DELETE", ROOT + "/owned", 204, Map.of()).reply("GET", ROOT + "/owned", 200, Map.of("id", "owned"));
      var api = new ScenarioApi(http, "token");
      assertThrows(ApiException.class, () -> {
        try (var resource = new ScenarioResource("owned", api, evidence)) {
          resource.create(new ObjectMapper().valueToTree(Map.of("id", "owned")), api);
        }
      });
    }
  }
  @Test void rejectedCreateVerifiesAbsenceWithoutDeletingAnything() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "denied")) {
      ingress.reply("GET", ROOT + "/owned", 404, Map.of()).reply("POST", ROOT, 403, Map.of())
          .reply("GET", ROOT + "/owned", 404, Map.of());
      var api = new ScenarioApi(http, "token");
      try (var resource = new ScenarioResource("owned", api, evidence)) {
        resource.create(new ObjectMapper().valueToTree(Map.of("id", "owned")), api).expect(403);
      }
    }
  }
  @Test void absentReadbackDoesNotResolveAnUnconfirmedCreate() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "unknown")) {
      ingress.reply("GET", ROOT + "/owned", 404, Map.of()).reply("POST", ROOT, 500, Map.of())
          .reply("GET", ROOT + "/owned", 404, Map.of());
      var api = new ScenarioApi(http, "token");
      var failure = assertThrows(ApiException.class, () -> {
        try (var resource = new ScenarioResource("owned", api, evidence)) {
          resource.create(new ObjectMapper().valueToTree(Map.of("id", "owned")), api).expect(201);
        }
      });
      assertEquals(1, failure.getSuppressed().length);
      assertTrue(failure.getSuppressed()[0].getMessage().contains("Unconfirmed scenario"));
    }
  }
}
