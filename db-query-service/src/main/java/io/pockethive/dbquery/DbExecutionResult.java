package io.pockethive.dbquery;

import java.util.List;
import java.util.Map;

record DbExecutionResult(List<Map<String, Object>> rows, Integer updateCount, long durationMs) {
  DbExecutionResult {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  int rowCount() {
    return rows.size();
  }
}
