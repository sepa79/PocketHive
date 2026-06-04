package io.pockethive.dbquery;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcDbStatementExecutorPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pockethive")
          .withUsername("pockethive")
          .withPassword("pockethive");

  @BeforeEach
  void setupSchema() throws Exception {
    try (var connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());
         var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS db_query_probe (
            probe_id TEXT PRIMARY KEY,
            status TEXT NOT NULL,
            remote_marker TEXT NOT NULL
          )
          """);
      statement.execute("TRUNCATE TABLE db_query_probe");
    }
  }

  @Test
  void executesInsertUpdateAndSelectThroughPostgresPool() throws Exception {
    try (JdbcDbStatementExecutor executor = new JdbcDbStatementExecutor()) {
      DbQueryWorkerConfig config = config();

      DbExecutionResult inserted = executor.execute(
          config,
          template(DbQueryTemplate.StatementType.INSERT, DbQueryTemplate.ResultMode.UPDATE_COUNT),
          new BoundSql(
              "insert into db_query_probe (probe_id, status, remote_marker) values (?, ?, ?)",
              List.of("P-1", "PENDING", "DBLINK-local"),
              List.of()));
      assertThat(inserted.updateCount()).isEqualTo(1);

      DbExecutionResult updated = executor.execute(
          config,
          template(DbQueryTemplate.StatementType.UPDATE, DbQueryTemplate.ResultMode.UPDATE_COUNT),
          new BoundSql(
              "update db_query_probe set status = ? where probe_id = ?",
              List.of("OK", "P-1"),
              List.of()));
      assertThat(updated.updateCount()).isEqualTo(1);

      DbExecutionResult selected = executor.execute(
          config,
          template(DbQueryTemplate.StatementType.SELECT, DbQueryTemplate.ResultMode.FIRST_ROW),
          new BoundSql(
              "select probe_id, status, remote_marker from db_query_probe where probe_id = ?",
              List.of("P-1"),
              List.of()));

      assertThat(selected.rows()).hasSize(1);
      Map<String, Object> row = selected.rows().getFirst();
      assertThat(row)
          .containsEntry("probe_id", "P-1")
          .containsEntry("status", "OK")
          .containsEntry("remote_marker", "DBLINK-local");
    }
  }

  private DbQueryWorkerConfig config() {
    return new DbQueryWorkerConfig(
        DbQueryWorkerConfig.Adapter.POSTGRES,
        "/unused",
        "db",
        "postgres-integration",
        2,
        5000,
        new DbQueryWorkerConfig.Connection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
        new DbQueryWorkerConfig.Pool(3, 1, 3000, 1000, 60000, 1800000),
        DbQueryWorkerConfig.Retry.noRetry(),
        Map.of());
  }

  private DbQueryTemplate template(DbQueryTemplate.StatementType statementType, DbQueryTemplate.ResultMode resultMode) {
    return new DbQueryTemplate(
        "db",
        "postgres-integration",
        DbQueryWorkerConfig.Adapter.POSTGRES,
        statementType,
        "unused",
        List.of(),
        new DbQueryTemplate.Result(resultMode),
        DbQueryTemplate.Validation.empty(),
        List.of());
  }
}
