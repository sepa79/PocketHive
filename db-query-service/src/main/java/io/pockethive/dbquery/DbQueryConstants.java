package io.pockethive.dbquery;

final class DbQueryConstants {

  static final String ROLE = "db-query";
  static final String WORKER_BEAN = "dbQueryWorker";

  static final String SOURCE_PAYLOAD = "payload";
  static final String SOURCE_HEADERS = "headers";
  static final String SOURCE_VARS = "vars";

  static final String RETRY_EXCEPTION = "EXCEPTION";
  static final String RETRY_SQLSTATE_CLASS_PREFIX = "SQLSTATE_CLASS_";

  static final String HEADER_ADAPTER = "x-ph-db-query-adapter";
  static final String HEADER_SERVICE_ID = "x-ph-db-query-service-id";
  static final String HEADER_QUERY_ID = "x-ph-db-query-query-id";
  static final String HEADER_STATEMENT_TYPE = "x-ph-db-query-statement-type";
  static final String HEADER_RESULT_MODE = "x-ph-db-query-result-mode";
  static final String HEADER_DURATION_MS = "x-ph-db-query-duration-ms";
  static final String HEADER_ATTEMPTS = "x-ph-db-query-attempts";
  static final String HEADER_ROWS = "x-ph-db-query-rows";
  static final String HEADER_UPDATE_COUNT = "x-ph-db-query-update-count";

  static final int OUTCOME_STATUS_SUCCESS = 200;
  static final String OUTCOME_BUSINESS_CODE_OK = "OK";
  static final String OUTCOME_DIMENSION_ADAPTER = "adapter";
  static final String OUTCOME_DIMENSION_SERVICE_ID = "service_id";
  static final String OUTCOME_DIMENSION_QUERY_ID = "query_id";
  static final String OUTCOME_DIMENSION_STATEMENT_TYPE = "statement_type";
  static final String OUTCOME_DIMENSION_RESULT_MODE = "result_mode";
  static final String OUTCOME_DIMENSION_ATTEMPTS = "attempts";
  static final String OUTCOME_DIMENSION_ROW_COUNT = "row_count";
  static final String OUTCOME_DIMENSION_UPDATE_COUNT = "update_count";

  private DbQueryConstants() {
  }
}
