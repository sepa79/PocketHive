package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.ApiSurface;

/**
 * Responsibility: read runnable catalogue evidence and compare explicit fixture scope.
 * Must not: calculate runnable permissions, normalize paths or invent catalogue entries.
 * Contract: docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
final class CatalogueAssertions {
  private CatalogueAssertions() {}
  static JsonNode catalogue(ApiRun run) throws Exception {
    var response = run.http.request("GET", ApiSurface.SCENARIO_MANAGER.publicPath("/api/templates"), null, run.token);
    run.evidence.record("runnable-catalogue", response);
    var result = run.http.tree(response.expect(200));
    assertTrue(result.isArray());
    return result;
  }
  static JsonNode entry(JsonNode catalogue, String id) {
    var matches = java.util.stream.StreamSupport.stream(catalogue.spliterator(), false)
        .filter(item -> id.equals(item.path("id").asText())).toList();
    assertEquals(1, matches.size(), "Expected one runnable fixture " + id);
    return matches.getFirst();
  }
  static boolean inFolder(JsonNode entry, String folder) {
    String actual = entry.required("folderPath").asText();
    return actual.equals(folder) || actual.startsWith(folder + "/");
  }
}
