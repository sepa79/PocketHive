package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.templating.api.SequenceAccess;
import io.pockethive.templating.api.TemplateRenderingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SequencePortRenderingTest {
    @Test
    void functionsDelegateArgumentsAndResetToTheInjectedOwner() {
        RecordingSequences sequences = new RecordingSequences("first");
        var renderer = new PebbleTemplateRenderer(sequences);
        assertThat(renderer.render("{{ eval(\"#sequence('orders', 'numeric', '%06d')\") }}", Map.of())).isEqualTo("first");
        assertThat(renderer.render("{{ eval(\"#sequenceWith('orders', 'hex', '%4S', 7L, 99L)\") }}", Map.of())).isEqualTo("first");
        assertThat(renderer.render("{{ eval(\"#resetSequence('orders')\") }}", Map.of())).isEqualTo("true");
        assertThat(sequences.calls).containsExactly("orders:numeric:%06d:1:-1", "orders:hex:%4S:7:99", "reset:orders");
    }

    @Test
    void syntaxValidationCannotExecuteEffectsAndDisabledRenderingFailsExplicitly() {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        String template = "{{ eval(\"#sequence('orders', 'numeric', '%06d')\") }}";
        renderer.validateSyntax(template);
        assertThatThrownBy(() -> renderer.render(template, Map.of()))
            .isInstanceOf(TemplateRenderingException.class)
            .hasStackTraceContaining("Sequence access is disabled");
    }

    private static class RecordingSequences implements SequenceAccess {
        private final List<String> calls = new ArrayList<>();
        private final String value;
        RecordingSequences(String value) { this.value = value; }
        public String next(String key, String mode, String format, long start, long max) {
            calls.add(key + ":" + mode + ":" + format + ":" + start + ":" + max);
            return value;
        }
        public boolean reset(String key) { calls.add("reset:" + key); return true; }
    }
}
