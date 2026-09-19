package io.pockethive.templating.api;

/**
 * Responsibility: explicitly reject sequence effects in syntax-only/offline compositions.
 * Must not: silently return generated-looking values or select another adapter.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
public enum DisabledSequenceAccess implements SequenceAccess {
    INSTANCE;
    @Override
    public String next(String key, String mode, String format, long startOffset, long maxSequence) {
        throw new IllegalStateException("Sequence access is disabled in this composition");
    }
    @Override
    public boolean reset(String key) {
        throw new IllegalStateException("Sequence access is disabled in this composition");
    }
}
