package io.pockethive.requesttemplates;

import java.util.List;

/**
 * Responsibility: enforce the portable-call execution constraints of HTTP Sequence.
 * Must not: parse worker configuration, compile templates, retry or execute requests.
 * Contract: RESP-HTTP-SEQUENCE-PORTABLE — docs/architecture/runtime-responsibilities.md#resp-http-sequence-portable.
 */
public final class HttpSequencePortablePolicy {
    private HttpSequencePortablePolicy() { }

    public static void validate(Integer maxAttempts, Boolean continueOnNon2xx, List<?> extracts) {
        // HTTP Sequence resolves absent/nonpositive retry attempts to one attempt.
        if ((maxAttempts != null && maxAttempts > 1) || Boolean.TRUE.equals(continueOnNon2xx)
            || (extracts != null && !extracts.isEmpty())) {
            throw new IllegalArgumentException(
                "Portable HTTP steps require one attempt, no continueOnNon2xx and no legacy extracts");
        }
    }
}
