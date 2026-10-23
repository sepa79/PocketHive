package io.pockethive.httpsequence;

import java.util.Map;

/**
 * Responsibility: carry the rendered HTTP request fields supplied to the executor.
 * Must not: render templates, resolve targets or execute requests.
 * Contract: RESP-HTTP-SEQUENCE-HTTP-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-http-sequence-http-execution.
 */
record RenderedCall(String method, String path, String body, Map<String, String> headers) {
}
