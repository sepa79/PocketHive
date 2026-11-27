package io.pockethive.requesttemplates;

import java.util.List;

/**
 * Responsibility: define and normalize the existing HTTP Sequence Extract configuration.
 * Must not: execute requests, resolve SUTs or implement portable admission policy.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
public record HttpSequenceExtract(
    String fromJsonPointer,
    String fromHeader,
    boolean fromStatus,
    String to,
    boolean required
) {

  public HttpSequenceExtract {
    fromJsonPointer = normalise(fromJsonPointer);
    fromHeader = normalise(fromHeader);
    to = normalise(to);
  }

  private static String normalise(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
