package io.pockethive.sink.clickhouse;

/**
 * Responsibility: present a rejected ClickHouse insert with a bounded response body.
 * Must not: retry, discard buffered data or decide a domain failure policy.
 * Contract: RESP-CLICKHOUSE-INSERT — docs/architecture/runtime-responsibilities.md#resp-clickhouse-insert.
 */
public final class ClickHouseInsertException extends IllegalStateException {
  private static final int MAX_ERROR_BODY_LENGTH = 500;
  private final int status;
  private final String body;

  ClickHouseInsertException(int status, String body) {
    super(describe(status, body, "ClickHouse insert", "…"));
    this.status = status;
    this.body = body;
  }

  public String describe(String operation, String truncationMarker) {
    return describe(status, body, operation, truncationMarker);
  }

  private static String describe(int status, String body, String operation, String truncationMarker) {
    String text = body == null ? "" : body.trim();
    if (text.length() > MAX_ERROR_BODY_LENGTH) {
      text = text.substring(0, MAX_ERROR_BODY_LENGTH) + truncationMarker;
    }
    return operation + " failed status=" + status + " body=" + text;
  }
}
