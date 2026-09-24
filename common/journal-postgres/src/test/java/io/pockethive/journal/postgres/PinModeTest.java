package io.pockethive.journal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PinModeTest {
    @Test
    void preservesInheritedDefaultAndCaseNormalization() {
        for (String value : new String[] {null, "", " ", "unknown"}) {
            assertThat(PinMode.fromNullable(value)).isEqualTo(PinMode.SLIM);
        }
        assertThat(PinMode.fromNullable(" full ")).isEqualTo(PinMode.FULL);
        assertThat(PinMode.fromNullable(" errors_only ")).isEqualTo(PinMode.ERRORS_ONLY);
    }
}
