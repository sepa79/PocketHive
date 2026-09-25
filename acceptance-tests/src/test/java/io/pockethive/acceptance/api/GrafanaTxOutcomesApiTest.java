package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GrafanaTxOutcomesApiTest {
  private static final Duration BUDGET = Duration.ofSeconds(1);
  private static final List<String> NAMES = List.of("swarmId", "sinkRole", "sinkInstance", "traceId", "callId",
      "processorStatus", "processorSuccess", "processorDurationMs");
  private GrafanaTxOutcomesApi api(PocketHiveHttp http) {
    return new GrafanaTxOutcomesApi(http, "grafana-user", "test-password", "explicit-source", "ph_tx_outcome_v2");
  }
  private static Map<String, Object> frame(List<String> names, List<?> values) {
    return Map.of("schema", Map.of("refId", "A", "fields", names.stream().map(name -> Map.of("name", name)).toList()),
        "data", Map.of("values", values));
  }
  private static Map<String, Object> response(Object frame) {
    return Map.of("results", Map.of("A", Map.of("status", 200, "frames", List.of(frame))));
  }
  @Test void readsScopedRowsWithoutLosingIntegerPrecision() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), BUDGET)) {
      ingress.replyWith("POST", "/grafana/api/ds/query", 200, body -> {
        var query = body.required("queries").get(0);
        assertEquals("explicit-source", query.required("datasource").required("uid").textValue());
        assertTrue(query.required("rawSql").textValue().contains("WHERE swarmId='owned\\'swarm'"));
        assertTrue(query.required("rawSql").textValue().endsWith("LIMIT 1000"));
        return response(frame(NAMES, List.of(List.of("owned'swarm"), List.of("postprocessor"), List.of("sink"),
            List.of("trace"), List.of("call"), List.of(200), List.of(1), List.of(9007199254740993L))));
      });
      var rows = api(http).read("owned'swarm", BUDGET);
      assertEquals(1, rows.size());
      assertEquals("trace", rows.getFirst().required("traceId").textValue());
      assertEquals(9007199254740993L, rows.getFirst().required("processorDurationMs").longValue());
    }
  }
  @Test void acceptsActualEmptyClickHouseFrame() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), BUDGET)) {
      ingress.reply("POST", "/grafana/api/ds/query", 200, response(frame(List.of(), List.of())));
      assertTrue(api(http).read("owned", BUDGET).isEmpty());
    }
  }
  @Test void nestedQueryErrorIsNotAnEmptySuccessfulObservation() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), BUDGET)) {
      ingress.reply("POST", "/grafana/api/ds/query", 200,
          Map.of("results", Map.of("A", Map.of("status", 500, "error", "database unavailable", "frames", List.of()))));
      assertThrows(AssertionError.class, () -> api(http).read("owned", BUDGET));
      ingress.reply("POST", "/grafana/api/ds/query", 200,
          Map.of("results", Map.of("A", Map.of("status", 200, "error", "query failed", "frames", List.of()))));
      assertThrows(AssertionError.class, () -> api(http).read("owned", BUDGET));
    }
  }
  @Test void rejectsMalformedAndForeignFrames() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), BUDGET)) {
      for (Object malformed : List.of(
          frame(NAMES, List.of(List.of("only one column"))),
          frame(List.of("swarmId", "swarmId"), List.of(List.of("one"), List.of("two"))),
          frame(NAMES, List.of(List.of("swarm"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
          Map.of("schema", Map.of("refId", "B", "fields", List.of()), "data", Map.of("values", List.of())))) {
        ingress.reply("POST", "/grafana/api/ds/query", 200, response(malformed));
        assertThrows(AssertionError.class, () -> api(http).read("owned", BUDGET));
      }
    }
  }
  @Test void refusesMissingScopeAndNonIdentifierTablesBeforeHttp() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), BUDGET)) {
      assertThrows(IllegalArgumentException.class, () -> api(http).read("", BUDGET));
      assertThrows(IllegalArgumentException.class, () -> new GrafanaTxOutcomesApi(http, "user", "pass", "uid", "table; SELECT 1"));
    }
  }
}
