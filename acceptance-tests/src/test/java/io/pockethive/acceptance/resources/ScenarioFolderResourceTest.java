package io.pockethive.acceptance.resources;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioFolderResourceTest {
  @TempDir Path reports;
  private static final String ROOT = "/scenario-manager/scenarios/folders";
  @Test void lostCreateResponseStillCleansAnObservedOwnedFolder() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "folder")) {
      ingress.reply("GET", ROOT, 200, List.of("other")).replyWith("POST", ROOT, 500, body -> {
        assertEquals("fixture/new folder", body.required("path").textValue()); return Map.of();
      }).reply("GET", ROOT, 200, List.of("other", "fixture/new folder"))
          .reply("DELETE", ROOT + "?path=fixture%2Fnew%20folder", 204, Map.of()).reply("GET", ROOT, 200, List.of("other"));
      var api = new ScenarioFolderApi(http, "token");
      assertThrows(ApiException.class, () -> {
        try (var resource = new ScenarioFolderResource("fixture/new folder", api, evidence)) { resource.create(api).expect(204); }
      });
    }
  }
  @Test void preExistingFolderIsNeverMutated() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "existing")) {
      ingress.reply("GET", ROOT, 200, List.of("owned"));
      var api = new ScenarioFolderApi(http, "token");
      try (var resource = new ScenarioFolderResource("owned", api, evidence)) {
        assertThrows(IllegalStateException.class, () -> resource.create(api));
      }
    }
  }
  @Test void deletionMustBeVisibleInFolderReadback() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "still-there")) {
      ingress.reply("GET", ROOT, 200, List.of()).reply("POST", ROOT, 204, Map.of())
          .reply("GET", ROOT, 200, List.of("owned")).reply("DELETE", ROOT + "?path=owned", 204, Map.of())
          .reply("GET", ROOT, 200, List.of("owned"));
      var api = new ScenarioFolderApi(http, "token");
      assertThrows(AssertionError.class, () -> {
        try (var resource = new ScenarioFolderResource("owned", api, evidence)) { resource.create(api).expect(204); }
      });
    }
  }
}
