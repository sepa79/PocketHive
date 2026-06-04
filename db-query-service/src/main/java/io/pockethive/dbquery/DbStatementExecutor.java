package io.pockethive.dbquery;

import java.sql.SQLException;

interface DbStatementExecutor extends AutoCloseable {

  DbExecutionResult execute(DbQueryWorkerConfig config, DbQueryTemplate template, BoundSql sql) throws SQLException;

  @Override
  default void close() {
  }
}
