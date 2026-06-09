package io.pockethive.dbquery;

import java.util.List;

record BoundSql(String jdbcSql, List<Object> values, List<DbQueryTemplate.Param> params) {
  BoundSql {
    if (jdbcSql == null || jdbcSql.isBlank()) {
      throw new IllegalArgumentException("jdbcSql is required");
    }
    values = values == null ? List.of() : List.copyOf(values);
    params = params == null ? List.of() : List.copyOf(params);
  }
}
