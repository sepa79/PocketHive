package io.pockethive.dbquery;

import java.util.List;

record DbQueryTemplate(
    String serviceId,
    String queryId,
    DbQueryWorkerConfig.Adapter adapter,
    StatementType statementType,
    String sqlTemplate,
    List<Param> params,
    Result result,
    Validation validation,
    List<Extract> extracts
) {

  DbQueryTemplate {
    serviceId = requireText(serviceId, "serviceId");
    queryId = requireText(queryId, "queryId");
    if (adapter == null) {
      throw new IllegalArgumentException("adapter is required");
    }
    if (statementType == null) {
      throw new IllegalArgumentException("statementType is required");
    }
    sqlTemplate = requireText(sqlTemplate, "sqlTemplate");
    params = params == null ? List.of() : List.copyOf(params);
    if (result == null || result.mode() == null) {
      throw new IllegalArgumentException("result.mode is required");
    }
    validation = validation == null ? Validation.empty() : validation;
    extracts = extracts == null ? List.of() : List.copyOf(extracts);
  }

  enum StatementType {
    SELECT,
    INSERT,
    UPDATE
  }

  enum ResultMode {
    FIRST_ROW,
    ALL_ROWS,
    UPDATE_COUNT,
    NONE
  }

  enum ParamType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    DECIMAL,
    BOOL,
    DATE,
    TIMESTAMP
  }

  record Param(String name, String source, ParamType type) {
    Param {
      name = requireText(name, "params[].name");
      source = requireText(source, "params[].source");
      if (type == null) {
        throw new IllegalArgumentException("params[].type is required");
      }
    }
  }

  record Result(ResultMode mode) {
  }

  record Validation(
      Integer minRows,
      Integer maxRows,
      Integer minAffectedRows,
      Integer maxAffectedRows,
      List<ColumnCheck> columns
  ) {
    Validation {
      if (minRows != null && minRows < 0) {
        throw new IllegalArgumentException("validation.minRows must be >= 0");
      }
      if (maxRows != null && maxRows < 0) {
        throw new IllegalArgumentException("validation.maxRows must be >= 0");
      }
      if (minAffectedRows != null && minAffectedRows < 0) {
        throw new IllegalArgumentException("validation.minAffectedRows must be >= 0");
      }
      if (maxAffectedRows != null && maxAffectedRows < 0) {
        throw new IllegalArgumentException("validation.maxAffectedRows must be >= 0");
      }
      columns = columns == null ? List.of() : List.copyOf(columns);
    }

    static Validation empty() {
      return new Validation(null, null, null, null, List.of());
    }
  }

  record ColumnCheck(String name, Object equals, Boolean notNull, String regex) {
    ColumnCheck {
      name = requireText(name, "validation.columns[].name");
      regex = DbQueryWorkerConfig.normalise(regex);
      if (equals == null && !Boolean.TRUE.equals(notNull) && regex == null) {
        throw new IllegalArgumentException("validation.columns[] must declare equals, notNull, or regex");
      }
    }
  }

  record Extract(String fromColumn, String to, boolean required) {
    Extract {
      fromColumn = requireText(fromColumn, "extracts[].fromColumn");
      to = requireText(to, "extracts[].to");
    }
  }

  static String key(String serviceId, String queryId) {
    return requireText(serviceId, "serviceId") + ":" + requireText(queryId, "queryId");
  }

  private static String requireText(String value, String field) {
    String normalised = DbQueryWorkerConfig.normalise(value);
    if (normalised == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalised;
  }
}
