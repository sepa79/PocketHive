package io.pockethive.dbquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

class DbQueryRunner {

  private final ObjectMapper mapper;
  private final DbQueryTemplateLoader templateLoader;
  private final NamedSqlParser sqlParser;
  private final DbStatementExecutor executor;

  private volatile String lastTemplateConfigKey;
  private volatile Map<String, DbQueryTemplate> templates;

  DbQueryRunner(ObjectMapper mapper,
                DbQueryTemplateLoader templateLoader,
                NamedSqlParser sqlParser,
                DbStatementExecutor executor) {
    this.mapper = mapper;
    this.templateLoader = templateLoader;
    this.sqlParser = sqlParser;
    this.executor = executor;
  }

  WorkItem run(WorkItem item, WorkerContext context, DbQueryWorkerConfig config) throws Exception {
    validateConfig(config);
    reloadTemplatesIfNeeded(config);
    DbQueryTemplate template = templates.get(DbQueryTemplate.key(config.serviceId(), config.queryId()));
    if (template == null) {
      throw new IllegalArgumentException("Missing DB query template for " + config.serviceId() + ":" + config.queryId());
    }
    if (template.adapter() != config.adapter()) {
      throw new IllegalArgumentException("Template adapter " + template.adapter()
          + " does not match worker adapter " + config.adapter());
    }

    DbValueResolver resolver = new DbValueResolver(mapper, item, config.vars());
    BoundSql boundSql = bindSql(template, resolver);
    Attempt attempt = executeWithRetry(config, template, boundSql);
    validateResult(template, attempt.result());

    Map<String, Object> payload = resolver.payloadAsMap();
    applyExtracts(template, payload, attempt.result());
    payload.put("dbQuery", output(config, template, attempt));

    Map<String, Object> stepHeaders = stepHeaders(config, template, attempt);
    return item.addStep(context.info(), mapper.writeValueAsString(payload), stepHeaders)
        .toBuilder()
        .contentType("application/json")
        .build();
  }

  private BoundSql bindSql(DbQueryTemplate template, DbValueResolver resolver) {
    NamedSql parsed = sqlParser.parse(template.sqlTemplate());
    Map<String, DbQueryTemplate.Param> paramsByName = new LinkedHashMap<>();
    for (DbQueryTemplate.Param param : template.params()) {
      DbQueryTemplate.Param previous = paramsByName.putIfAbsent(param.name(), param);
      if (previous != null) {
        throw new IllegalArgumentException("Duplicate DB query param " + param.name());
      }
    }

    java.util.ArrayList<Object> values = new java.util.ArrayList<>();
    java.util.ArrayList<DbQueryTemplate.Param> orderedParams = new java.util.ArrayList<>();
    for (String name : parsed.parameterNames()) {
      DbQueryTemplate.Param param = paramsByName.get(name);
      if (param == null) {
        throw new IllegalArgumentException("SQL placeholder :" + name + " has no params[] definition");
      }
      orderedParams.add(param);
      values.add(resolver.resolve(param));
    }
    for (String name : paramsByName.keySet()) {
      if (!parsed.parameterNames().contains(name)) {
        throw new IllegalArgumentException("params[] definition " + name + " is not used by sqlTemplate");
      }
    }
    return new BoundSql(parsed.jdbcSql(), values, orderedParams);
  }

  private Attempt executeWithRetry(DbQueryWorkerConfig config,
                                   DbQueryTemplate template,
                                   BoundSql sql) throws Exception {
    DbQueryWorkerConfig.Retry retry = config.retry();
    int maxAttempts = retry == null ? 1 : retry.maxAttempts();
    Exception last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        DbExecutionResult result = executor.execute(config, template, sql);
        return new Attempt(result, attempt);
      } catch (Exception ex) {
        last = ex;
        if (attempt >= maxAttempts || !shouldRetry(retry, ex)) {
          throw ex;
        }
        long sleepMs = computeBackoffMs(retry, attempt);
        if (sleepMs > 0L) {
          try {
            Thread.sleep(sleepMs);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
          }
        }
      }
    }
    throw last == null ? new IllegalStateException("DB query failed without an exception") : last;
  }

  private boolean shouldRetry(DbQueryWorkerConfig.Retry retry, Exception ex) {
    if (retry == null || retry.on().isEmpty()) {
      return false;
    }
    SQLException sql = findSqlException(ex);
    for (String tokenRaw : retry.on()) {
      String token = normaliseToken(tokenRaw);
      if (token == null) {
        continue;
      }
      if (DbQueryConstants.RETRY_EXCEPTION.equals(token)) {
        return true;
      }
      if (token.startsWith(DbQueryConstants.RETRY_SQLSTATE_CLASS_PREFIX) && sql != null) {
        String expected = token.substring(DbQueryConstants.RETRY_SQLSTATE_CLASS_PREFIX.length());
        String state = sql.getSQLState();
        if (state != null && state.length() >= 2 && state.substring(0, 2).equalsIgnoreCase(expected)) {
          return true;
        }
      }
    }
    return false;
  }

  private long computeBackoffMs(DbQueryWorkerConfig.Retry retry, int attempt) {
    if (retry == null || retry.initialBackoffMs() <= 0L) {
      return 0L;
    }
    double multiplier = Math.pow(retry.backoffMultiplier(), Math.max(0, attempt - 1));
    long computed = Math.round(retry.initialBackoffMs() * multiplier);
    if (retry.maxBackoffMs() > 0L) {
      return Math.min(computed, retry.maxBackoffMs());
    }
    return computed;
  }

  private SQLException findSqlException(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof SQLException sql) {
        return sql;
      }
      current = current.getCause();
    }
    return null;
  }

  private String normaliseToken(String tokenRaw) {
    String token = DbQueryWorkerConfig.normalise(tokenRaw);
    return token == null ? null : token.toUpperCase(Locale.ROOT);
  }

  private void validateResult(DbQueryTemplate template, DbExecutionResult result) {
    DbQueryTemplate.Validation validation = template.validation();
    int rowCount = result.rowCount();
    Integer updateCount = result.updateCount();
    if (validation.minRows() != null && rowCount < validation.minRows()) {
      throw new IllegalStateException("DB query row count " + rowCount + " is below minRows " + validation.minRows());
    }
    if (validation.maxRows() != null && rowCount > validation.maxRows()) {
      throw new IllegalStateException("DB query row count " + rowCount + " is above maxRows " + validation.maxRows());
    }
    if (validation.minAffectedRows() != null && (updateCount == null || updateCount < validation.minAffectedRows())) {
      throw new IllegalStateException("DB query update count " + updateCount
          + " is below minAffectedRows " + validation.minAffectedRows());
    }
    if (validation.maxAffectedRows() != null && (updateCount == null || updateCount > validation.maxAffectedRows())) {
      throw new IllegalStateException("DB query update count " + updateCount
          + " is above maxAffectedRows " + validation.maxAffectedRows());
    }
    if (!validation.columns().isEmpty()) {
      if (result.rows().isEmpty()) {
        throw new IllegalStateException("DB query column validation requires at least one row");
      }
      Map<String, Object> first = result.rows().getFirst();
      for (DbQueryTemplate.ColumnCheck check : validation.columns()) {
        Object value = first.get(check.name());
        validateColumn(check, value);
      }
    }
  }

  private void validateColumn(DbQueryTemplate.ColumnCheck check, Object value) {
    if (Boolean.TRUE.equals(check.notNull()) && value == null) {
      throw new IllegalStateException("DB query column " + check.name() + " is null");
    }
    if (check.equals() != null && (value == null || !check.equals().toString().equals(value.toString()))) {
      throw new IllegalStateException("DB query column " + check.name() + " expected "
          + check.equals() + " but was " + value);
    }
    if (check.regex() != null && (value == null || !Pattern.compile(check.regex()).matcher(value.toString()).find())) {
      throw new IllegalStateException("DB query column " + check.name() + " did not match " + check.regex());
    }
  }

  private void applyExtracts(DbQueryTemplate template, Map<String, Object> payload, DbExecutionResult result) {
    if (template.extracts().isEmpty()) {
      return;
    }
    Map<String, Object> first = result.rows().isEmpty() ? Map.of() : result.rows().getFirst();
    for (DbQueryTemplate.Extract extract : template.extracts()) {
      Object value = first.get(extract.fromColumn());
      if (value == null) {
        if (extract.required()) {
          throw new IllegalStateException("Required DB query extract missing from column " + extract.fromColumn());
        }
        continue;
      }
      putPath(payload, extract.to(), value);
    }
  }

  private Map<String, Object> output(DbQueryWorkerConfig config, DbQueryTemplate template, Attempt attempt) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("adapter", config.adapter().name());
    out.put("serviceId", template.serviceId());
    out.put("queryId", template.queryId());
    out.put("statementType", template.statementType().name());
    out.put("resultMode", template.result().mode().name());
    out.put("attempts", attempt.attempts());
    out.put("durationMs", attempt.result().durationMs());
    out.put("rowCount", attempt.result().rowCount());
    if (attempt.result().updateCount() != null) {
      out.put("updateCount", attempt.result().updateCount());
    }
    if (template.result().mode() == DbQueryTemplate.ResultMode.FIRST_ROW) {
      out.put("row", attempt.result().rows().isEmpty() ? null : attempt.result().rows().getFirst());
    } else if (template.result().mode() == DbQueryTemplate.ResultMode.ALL_ROWS) {
      out.put("rows", attempt.result().rows());
    }
    return out;
  }

  private Map<String, Object> stepHeaders(DbQueryWorkerConfig config, DbQueryTemplate template, Attempt attempt) {
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put(DbQueryConstants.HEADER_ADAPTER, config.adapter().name());
    headers.put(DbQueryConstants.HEADER_SERVICE_ID, template.serviceId());
    headers.put(DbQueryConstants.HEADER_QUERY_ID, template.queryId());
    headers.put(DbQueryConstants.HEADER_STATEMENT_TYPE, template.statementType().name());
    headers.put(DbQueryConstants.HEADER_RESULT_MODE, template.result().mode().name());
    headers.put(DbQueryConstants.HEADER_DURATION_MS, attempt.result().durationMs());
    headers.put(DbQueryConstants.HEADER_ATTEMPTS, attempt.attempts());
    headers.put(DbQueryConstants.HEADER_ROWS, attempt.result().rowCount());
    if (attempt.result().updateCount() != null) {
      headers.put(DbQueryConstants.HEADER_UPDATE_COUNT, attempt.result().updateCount());
    }
    addOutcomeHeaders(headers, config, template, attempt);
    return headers;
  }

  private void addOutcomeHeaders(Map<String, Object> headers,
                                 DbQueryWorkerConfig config,
                                 DbQueryTemplate template,
                                 Attempt attempt) {
    DbExecutionResult result = attempt.result();
    headers.put(OutcomeHeaders.CALL_ID, template.serviceId() + "/" + template.queryId());
    headers.put(OutcomeHeaders.PROCESSOR_STATUS, DbQueryConstants.OUTCOME_STATUS_SUCCESS);
    headers.put(OutcomeHeaders.PROCESSOR_SUCCESS, true);
    headers.put(OutcomeHeaders.PROCESSOR_DURATION_MS, result.durationMs());
    headers.put(OutcomeHeaders.BUSINESS_CODE, DbQueryConstants.OUTCOME_BUSINESS_CODE_OK);
    headers.put(OutcomeHeaders.BUSINESS_SUCCESS, true);
    headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_ADAPTER), config.adapter().name());
    headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_SERVICE_ID), template.serviceId());
    headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_QUERY_ID), template.queryId());
    headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_STATEMENT_TYPE), template.statementType().name());
    headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_RESULT_MODE), template.result().mode().name());
    headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_ATTEMPTS), attempt.attempts());
    headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_ROW_COUNT), result.rowCount());
    if (result.updateCount() != null) {
      headers.put(OutcomeHeaders.dimension(DbQueryConstants.OUTCOME_DIMENSION_UPDATE_COUNT), result.updateCount());
    }
  }

  private void validateConfig(DbQueryWorkerConfig config) {
    if (config.adapter() == null) {
      throw new IllegalArgumentException("adapter is required");
    }
    if (config.serviceId() == null) {
      throw new IllegalArgumentException("serviceId is required");
    }
    if (config.queryId() == null) {
      throw new IllegalArgumentException("queryId is required");
    }
    if (config.connection() == null) {
      throw new IllegalArgumentException("connection is required");
    }
    if (config.connection().jdbcUrl() == null) {
      throw new IllegalArgumentException("connection.jdbcUrl is required");
    }
    if (config.connection().username() == null) {
      throw new IllegalArgumentException("connection.username is required");
    }
  }

  private void reloadTemplatesIfNeeded(DbQueryWorkerConfig config) {
    String key = config.templateRoot() + "::" + config.serviceId();
    Map<String, DbQueryTemplate> current = templates;
    if (current == null || !key.equals(lastTemplateConfigKey)) {
      templates = templateLoader.load(config.templateRoot(), config.serviceId());
      lastTemplateConfigKey = key;
    }
  }

  @SuppressWarnings("unchecked")
  private static void putPath(Map<String, Object> payload, String dottedPath, Object value) {
    String[] parts = dottedPath.split("\\.");
    Map<String, Object> current = payload;
    for (int i = 0; i < parts.length - 1; i++) {
      String part = parts[i];
      Object next = current.get(part);
      if (!(next instanceof Map<?, ?> nextMap)) {
        Map<String, Object> created = new LinkedHashMap<>();
        current.put(part, created);
        current = created;
      } else {
        current = (Map<String, Object>) nextMap;
      }
    }
    current.put(parts[parts.length - 1], value);
  }

  private record Attempt(DbExecutionResult result, int attempts) {
  }
}
