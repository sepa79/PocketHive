package io.pockethive.sink.clickhouse;

import java.util.List;

/**
 * Responsibility: send serialized rows to one prepared ClickHouse destination.
 * Must not: buffer domain events, retry failed batches or select configuration.
 * Contract: RESP-CLICKHOUSE-INSERT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-insert; success means an HTTP 2xx response.
 */
@FunctionalInterface
public interface ClickHouseInsert {
  void write(List<String> rows) throws Exception;
}
