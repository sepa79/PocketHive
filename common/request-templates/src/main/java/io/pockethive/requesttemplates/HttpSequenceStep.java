package io.pockethive.requesttemplates;

import java.util.List;

/**
 * Responsibility: define and normalize the existing HTTP Sequence Step configuration.
 * Must not: execute requests, resolve SUTs or implement portable admission policy.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
public record HttpSequenceStep(
    String id,
    String callId,
    String serviceId,
    boolean continueOnNon2xx,
    HttpSequenceRetry retry,
    List<HttpSequenceExtract> extracts,
    List<HttpSequenceSetValue> set,
    String sutEndpointId,
    String baseUrl
) {

  public HttpSequenceStep(String id,
              String callId,
              String serviceId,
              boolean continueOnNon2xx,
              HttpSequenceRetry retry,
              List<HttpSequenceExtract> extracts,
              List<HttpSequenceSetValue> set) {
    this(id, callId, serviceId, continueOnNon2xx, retry, extracts, set, null, null);
  }

  public HttpSequenceStep {
    id = normalise(id);
    callId = normalise(callId);
    serviceId = normalise(serviceId);
    retry = retry == null ? HttpSequenceRetry.defaults() : retry;
    extracts = extracts == null ? List.of() : List.copyOf(extracts);
    set = set == null ? List.of() : List.copyOf(set);
    sutEndpointId = trim(sutEndpointId);
    baseUrl = trim(baseUrl);
  }

  private static String normalise(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
