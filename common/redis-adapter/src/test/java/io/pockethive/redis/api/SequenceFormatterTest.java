package io.pockethive.redis.api;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SequenceFormatterTest {
    @Test void preservesOdometerOrderingAndHexDigits() {
        var alpha = SequenceFormatter.prepare("ALPHA", "%2S%02d");
        assertThat(alpha.format(1, 1, 0)).isEqualTo("AA00");
        assertThat(alpha.format(2, 1, 0)).isEqualTo("AA01");
        assertThat(alpha.format(101, 1, 0)).isEqualTo("AB00");
        var hex = SequenceFormatter.prepare("HEX", "%2S");
        assertThat(hex.format(16, 1, 0)).isEqualTo("0F");
        assertThat(hex.format(17, 1, 0)).isEqualTo("10");
    }
    @Test void preservesOffsetAndExplicitWrapAndRejectsInvalidFormats() {
        var numeric = SequenceFormatter.prepare("NUMERIC", "%02d");
        assertThat(numeric.format(1, 3, 4)).isEqualTo("02");
        assertThat(numeric.format(3, 3, 4)).isEqualTo("00");
        assertThatThrownBy(() -> SequenceFormatter.prepare("unknown", "%d")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SequenceFormatter.prepare("NUMERIC", "literal")).isInstanceOf(IllegalArgumentException.class);
    }
}
