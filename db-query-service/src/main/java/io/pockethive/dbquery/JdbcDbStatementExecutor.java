package io.pockethive.dbquery;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
class JdbcDbStatementExecutor implements DbStatementExecutor {

  private final AtomicReference<PoolState> state = new AtomicReference<>();

  @Override
  public DbExecutionResult execute(DbQueryWorkerConfig config, DbQueryTemplate template, BoundSql sql) throws SQLException {
    HikariDataSource dataSource = dataSource(config);
    long start = System.nanoTime();
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql.jdbcSql())) {
      int timeoutSeconds = queryTimeoutSeconds(config.queryTimeoutMs());
      if (timeoutSeconds > 0) {
        statement.setQueryTimeout(timeoutSeconds);
      }
      for (int i = 0; i < sql.values().size(); i++) {
        statement.setObject(i + 1, sql.values().get(i));
      }
      DbExecutionResult result = executeStatement(template, statement, start);
      return result;
    }
  }

  private DbExecutionResult executeStatement(DbQueryTemplate template,
                                             PreparedStatement statement,
                                             long start) throws SQLException {
    return switch (template.statementType()) {
      case SELECT -> executeSelect(template, statement, start);
      case INSERT, UPDATE -> executeUpdate(template, statement, start);
    };
  }

  private DbExecutionResult executeSelect(DbQueryTemplate template,
                                          PreparedStatement statement,
                                          long start) throws SQLException {
    if (template.result().mode() != DbQueryTemplate.ResultMode.FIRST_ROW
        && template.result().mode() != DbQueryTemplate.ResultMode.ALL_ROWS
        && template.result().mode() != DbQueryTemplate.ResultMode.NONE) {
      throw new IllegalArgumentException("SELECT requires result.mode FIRST_ROW, ALL_ROWS, or NONE");
    }
    boolean hasResultSet = statement.execute();
    if (!hasResultSet) {
      throw new SQLException("SELECT did not return a result set");
    }
    if (template.result().mode() == DbQueryTemplate.ResultMode.NONE) {
      return new DbExecutionResult(List.of(), null, elapsedMs(start));
    }
    try (ResultSet rs = statement.getResultSet()) {
      List<Map<String, Object>> rows = readRows(rs, template.result().mode() == DbQueryTemplate.ResultMode.FIRST_ROW);
      return new DbExecutionResult(rows, null, elapsedMs(start));
    }
  }

  private DbExecutionResult executeUpdate(DbQueryTemplate template,
                                          PreparedStatement statement,
                                          long start) throws SQLException {
    if (template.result().mode() != DbQueryTemplate.ResultMode.UPDATE_COUNT
        && template.result().mode() != DbQueryTemplate.ResultMode.NONE) {
      throw new IllegalArgumentException(template.statementType() + " requires result.mode UPDATE_COUNT or NONE");
    }
    int count = statement.executeUpdate();
    Integer updateCount = template.result().mode() == DbQueryTemplate.ResultMode.NONE ? null : count;
    return new DbExecutionResult(List.of(), updateCount, elapsedMs(start));
  }

  private List<Map<String, Object>> readRows(ResultSet rs, boolean firstOnly) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    ResultSetMetaData meta = rs.getMetaData();
    int columns = meta.getColumnCount();
    while (rs.next()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 1; i <= columns; i++) {
        row.put(meta.getColumnLabel(i), rs.getObject(i));
      }
      rows.add(row);
      if (firstOnly) {
        break;
      }
    }
    return rows;
  }

  private HikariDataSource dataSource(DbQueryWorkerConfig config) {
    PoolKey key = PoolKey.from(config);
    PoolState current = state.get();
    if (current != null && current.key().equals(key)) {
      return current.dataSource();
    }
    synchronized (this) {
      current = state.get();
      if (current != null && current.key().equals(key)) {
        return current.dataSource();
      }
      HikariDataSource next = createDataSource(config, key);
      PoolState previous = state.getAndSet(new PoolState(key, next));
      if (previous != null) {
        previous.dataSource().close();
      }
      return next;
    }
  }

  private HikariDataSource createDataSource(DbQueryWorkerConfig config, PoolKey key) {
    HikariConfig hikari = new HikariConfig();
    hikari.setPoolName("pockethive-db-query-" + config.adapter().name().toLowerCase(java.util.Locale.ROOT));
    hikari.setDriverClassName(config.adapter().driverClassName());
    hikari.setJdbcUrl(key.jdbcUrl());
    hikari.setUsername(key.username());
    hikari.setPassword(key.password());
    hikari.setMaximumPoolSize(key.pool().maxSize());
    hikari.setMinimumIdle(key.pool().minIdle());
    hikari.setConnectionTimeout(key.pool().connectionTimeoutMs());
    hikari.setValidationTimeout(key.pool().validationTimeoutMs());
    if (key.pool().idleTimeoutMs() > 0L) {
      hikari.setIdleTimeout(key.pool().idleTimeoutMs());
    }
    if (key.pool().maxLifetimeMs() > 0L) {
      hikari.setMaxLifetime(key.pool().maxLifetimeMs());
    }
    return new HikariDataSource(hikari);
  }

  private static int queryTimeoutSeconds(int timeoutMs) {
    if (timeoutMs <= 0) {
      return 0;
    }
    return Math.max(1, (int) Math.ceil(timeoutMs / 1000.0));
  }

  private static long elapsedMs(long startNanos) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  @Override
  public void close() {
    PoolState current = state.getAndSet(null);
    if (current != null) {
      current.dataSource().close();
    }
  }

  private record PoolKey(
      DbQueryWorkerConfig.Adapter adapter,
      String jdbcUrl,
      String username,
      String password,
      DbQueryWorkerConfig.Pool pool
  ) {
    static PoolKey from(DbQueryWorkerConfig config) {
      if (config.adapter() == null) {
        throw new IllegalArgumentException("adapter is required");
      }
      if (config.connection() == null || config.connection().jdbcUrl() == null) {
        throw new IllegalArgumentException("connection.jdbcUrl is required");
      }
      if (config.connection().username() == null) {
        throw new IllegalArgumentException("connection.username is required");
      }
      return new PoolKey(
          config.adapter(),
          config.connection().jdbcUrl(),
          config.connection().username(),
          config.connection().password(),
          config.pool());
    }
  }

  private record PoolState(PoolKey key, HikariDataSource dataSource) {
  }
}
