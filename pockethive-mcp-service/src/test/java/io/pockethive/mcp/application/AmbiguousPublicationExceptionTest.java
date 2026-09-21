package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AmbiguousPublicationExceptionTest {

    @Test
    void exposesTheExactAttemptThatRequiresReconciliation() {
        RuntimeException cause = new RuntimeException("owner outcome unavailable");

        AmbiguousPublicationException exception = new AmbiguousPublicationException("attempt-1", cause);

        assertThat(exception.attemptId()).isEqualTo("attempt-1");
        assertThat(exception).hasMessage("PUBLICATION_RESULT_AMBIGUOUS: attempt-1")
            .hasCause(cause);
    }
}
