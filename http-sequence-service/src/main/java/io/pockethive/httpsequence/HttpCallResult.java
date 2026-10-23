package io.pockethive.httpsequence;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: carry the observed HTTP status, headers, body and transport error.
 * Must not: interpret response documents, apply extracts or decide retries.
 * Contract: RESP-HTTP-SEQUENCE-HTTP-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-http-sequence-http-execution.
 */
record HttpCallResult(int statusCode, Map<String, List<String>> headers, String body, String error) {
}
