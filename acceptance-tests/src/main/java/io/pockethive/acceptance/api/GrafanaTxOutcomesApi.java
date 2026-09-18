package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: read a scoped outcome table projection through Grafana's public datasource query API.
 * Must not: write telemetry, derive transaction success, use native DB access or resolve sink configuration.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#transaction-outcome-persistence-acceptance-da-3.
 */
public final class GrafanaTxOutcomesApi {
  private static final List<String> COLUMNS = List.of("swarmId", "sinkRole", "sinkInstance", "traceId", "callId",
      "processorStatus", "processorSuccess", "processorDurationMs");
  private final PocketHiveHttp http;
  private final String username;
  private final String password;
  private final String datasourceUid;
  private final String table;

  public GrafanaTxOutcomesApi(PocketHiveHttp http, String username, String password, String datasourceUid, String table) {
    if (!table.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
      throw new IllegalArgumentException("Expected an explicit table or database.table identifier");
    }
    this.http = http;
    this.username = username;
    this.password = password;
    this.datasourceUid = datasourceUid;
    this.table = table;
  }

  public List<JsonNode> read(String swarmId, Duration budget) throws IOException, InterruptedException {
    if (swarmId == null || swarmId.isBlank()) throw new IllegalArgumentException("Explicit swarmId is required");
    String literal = swarmId.replace("\\", "\\\\").replace("'", "\\'");
    String sql = "SELECT " + String.join(",", COLUMNS) + " FROM " + table
        + " WHERE swarmId='" + literal + "' ORDER BY eventTime LIMIT 1000";
    var query = Map.of("refId", "A", "datasource", Map.of("type", "grafana-clickhouse-datasource", "uid", datasourceUid),
        "rawSql", sql, "format", 1, "queryType", "sql", "editorType", "sql");
    var response = http.tree(http.requestWithBasicAuth("POST", ApiSurface.GRAFANA.publicPath("/api/ds/query"),
        Map.of("queries", List.of(query), "from", "now-1h", "to", "now"), username, password, budget).expect(200));
    var result = response.required("results").required("A");
    if (!result.required("status").isIntegralNumber() || result.required("status").intValue() != 200
        || result.hasNonNull("error")) throw new AssertionError("Grafana outcome query failed: " + result);
    var frames = result.required("frames");
    if (!frames.isArray()) throw new AssertionError("Grafana frames must be an array");
    var rows = new ArrayList<JsonNode>();
    for (var frame : frames) {
      var schema = frame.required("schema");
      if (!"A".equals(schema.required("refId").textValue())) throw new AssertionError("Unrelated Grafana frame");
      var fields = schema.required("fields");
      var values = frame.required("data").required("values");
      if (!fields.isArray() || !values.isArray() || fields.size() != values.size()) {
        throw new AssertionError("Grafana field/value column mismatch");
      }
      // Grafana returns an empty schema/values pair for a valid zero-row ClickHouse result.
      if (fields.isEmpty()) continue;
      var names = new HashSet<String>();
      int count = -1;
      for (int column = 0; column < fields.size(); column++) {
        String name = fields.get(column).required("name").textValue();
        if (!names.add(name)) throw new AssertionError("Duplicate Grafana column: " + name);
        if (!values.get(column).isArray()) throw new AssertionError("Grafana column is not an array");
        if (count == -1) count = values.get(column).size();
        if (count != values.get(column).size()) throw new AssertionError("Grafana columns have different row counts");
      }
      if (!names.equals(new HashSet<>(COLUMNS))) throw new AssertionError("Unexpected outcome columns: " + names);
      for (int row = 0; row < count; row++) {
        var item = JsonNodeFactory.instance.objectNode();
        for (int column = 0; column < fields.size(); column++) {
          item.set(fields.get(column).required("name").textValue(), values.get(column).get(row));
        }
        rows.add(item);
      }
    }
    return List.copyOf(rows);
  }
}
