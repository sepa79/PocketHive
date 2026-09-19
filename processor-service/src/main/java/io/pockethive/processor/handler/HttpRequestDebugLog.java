package io.pockethive.processor.handler;

import io.pockethive.observability.HttpHeaderRedactor;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Responsibility: emit processor HTTP request diagnostics with credential headers redacted.
 * Must not: mutate transport headers, apply credentials, or redact collected runtime logs.
 * Contract: RESP-PROCESSOR-HTTP-REQUEST-LOG — docs/architecture/runtime-responsibilities.md#resp-processor-http-request-log.
 */
final class HttpRequestDebugLog {
  private HttpRequestDebugLog() {
  }

  static void log(Logger logger, String method, URI target, Map<String, String> headers, String body) {
    if (!logger.isDebugEnabled()) {
      return;
    }
    Map<String, String> safeHeaders = HttpHeaderRedactor.redact(headers);
    safeHeaders.forEach((name, value) -> logger.debug("header {}={}", name, value));
    logger.debug("HTTP REQUEST {} {} headers={} body={}", method, target, safeHeaders, body);
  }
}
