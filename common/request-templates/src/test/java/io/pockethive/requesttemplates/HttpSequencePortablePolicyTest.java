package io.pockethive.requesttemplates;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpSequencePortablePolicyTest {
    @Test
    void acceptsTheEffectiveSingleAttemptDefaults() {
        assertThatCode(() -> HttpSequencePortablePolicy.validate(null, null, null)).doesNotThrowAnyException();
        for (int attempts : List.of(-1, 0, 1)) {
            assertThatCode(() -> HttpSequencePortablePolicy.validate(attempts, false, List.of()))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsEachUnsupportedExecutionOption() {
        assertThatThrownBy(() -> HttpSequencePortablePolicy.validate(2, false, List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one attempt");
        assertThatThrownBy(() -> HttpSequencePortablePolicy.validate(1, true, List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("continueOnNon2xx");
        assertThatThrownBy(() -> HttpSequencePortablePolicy.validate(1, false, List.of("extract")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("legacy extracts");
    }
}
