package io.pockethive.requesttemplates;

import java.util.List;

/**
 * Responsibility: define and normalize the existing HTTP Sequence Retry configuration.
 * Must not: execute requests, resolve SUTs or implement portable admission policy.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
public record HttpSequenceRetry(
    int maxAttempts,
    long initialBackoffMs,
    double backoffMultiplier,
    long maxBackoffMs,
    List<String> on
) {

  public HttpSequenceRetry {
    maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
    initialBackoffMs = Math.max(0L, initialBackoffMs);
    backoffMultiplier = backoffMultiplier <= 0.0 ? 1.0 : backoffMultiplier;
    maxBackoffMs = maxBackoffMs <= 0L ? initialBackoffMs : maxBackoffMs;
    on = on == null ? List.of() : List.copyOf(on);
  }

  static HttpSequenceRetry defaults() {
    return new HttpSequenceRetry(1, 0L, 1.0, 0L, List.of());
  }
}
