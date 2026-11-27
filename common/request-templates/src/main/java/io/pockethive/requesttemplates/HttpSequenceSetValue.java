package io.pockethive.requesttemplates;

import java.util.Map;

/**
 * Responsibility: define and normalize the existing HTTP Sequence SetValue configuration.
 * Must not: execute requests, resolve SUTs or implement portable admission policy.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
public record HttpSequenceSetValue(
    String to,
    String template,
    boolean parseJson
) {

  public HttpSequenceSetValue {
    to = normalise(to);
    template = template == null ? "" : template;
  }

  /** Read-only source projection; unrelated/deferred set options remain owned by runtime binding. */
  public static String source(Map<?, ?> fields) {
    return fields.get("template") instanceof String source ? source : null;
  }

  private static String normalise(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
