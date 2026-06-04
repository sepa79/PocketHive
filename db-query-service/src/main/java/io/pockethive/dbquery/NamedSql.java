package io.pockethive.dbquery;

import java.util.List;

record NamedSql(String jdbcSql, List<String> parameterNames) {
  NamedSql {
    if (jdbcSql == null || jdbcSql.isBlank()) {
      throw new IllegalArgumentException("jdbcSql is required");
    }
    parameterNames = parameterNames == null ? List.of() : List.copyOf(parameterNames);
  }
}
