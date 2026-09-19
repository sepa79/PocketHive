package io.pockethive.templating;

import io.pockethive.templating.api.SequenceAccess;
import java.util.Objects;

/**
 * Responsibility: map template sequence function arguments to the injected sequence port.
 * Must not: select a Redis connection or implement sequence generation.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
final class SequenceFunctions {
    private final SequenceAccess sequences;
    SequenceFunctions(SequenceAccess sequences) { this.sequences = Objects.requireNonNull(sequences, "sequences"); }

  public String sequence(String key, String mode, String format) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
    if (mode == null || mode.isBlank()) throw new IllegalArgumentException("mode required");
    if (format == null || format.isBlank()) throw new IllegalArgumentException("format required");
    return sequences.next(key, mode, format, 1, -1);
  }

  public String sequence(String key, String mode, String format, Long startOffset, Long maxSequence) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
    if (mode == null || mode.isBlank()) throw new IllegalArgumentException("mode required");
    if (format == null || format.isBlank()) throw new IllegalArgumentException("format required");
    long start = startOffset != null ? startOffset : 1;
    long max = maxSequence != null ? maxSequence : -1;
    if (start < 1) throw new IllegalArgumentException("startOffset must be >= 1");
    return sequences.next(key, mode, format, start, max);
  }

  public boolean resetSequence(String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
    return sequences.reset(key);
  }

}
