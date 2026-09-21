package io.pockethive.templating.api;

/**
 * Responsibility: expose canonical sequence generation and reset effects.
 * Must not: select connections or provide a global default implementation.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
public interface SequenceAccess {
    String next(String key, String mode, String format, long startOffset, long maxSequence);
    boolean reset(String key);
}
