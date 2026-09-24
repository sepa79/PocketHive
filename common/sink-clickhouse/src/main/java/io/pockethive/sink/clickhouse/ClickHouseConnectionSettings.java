package io.pockethive.sink.clickhouse;

/**
 * Responsibility: expose the existing property owner's connection settings read-only.
 * Must not: supply defaults, validate domain settings or retain a second configuration.
 * Contract: RESP-CLICKHOUSE-INSERT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-insert.
 */
public interface ClickHouseConnectionSettings {
  String getEndpoint();
  String getTable();
  String getUsername();
  String getPassword();
  int getConnectTimeoutMs();
  int getReadTimeoutMs();
}
