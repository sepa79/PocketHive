package io.pockethive.worker.sdk.config;

import io.pockethive.templating.api.SequenceAccess;
import java.util.Objects;

/**
 * Responsibility: expose the application-owned sequence configuration through its rendering port.
 * Must not: choose defaults, open clients or own configuration state.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
public final class ConfiguredSequenceAccess implements SequenceAccess {
    private final RedisSequenceConfiguration sequences;
    public ConfiguredSequenceAccess(RedisSequenceConfiguration sequences) {
        this.sequences = Objects.requireNonNull(sequences, "sequences");
    }
    public String next(String key, String mode, String format, long startOffset, long maxSequence) {
        return sequences.next(key, mode, format, startOffset, maxSequence);
    }
    public boolean reset(String key) { return sequences.reset(key); }
}
