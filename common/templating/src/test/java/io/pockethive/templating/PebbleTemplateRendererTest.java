package io.pockethive.templating;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PebbleTemplateRendererTest {
    private final PebbleTemplateRenderer renderer = new PebbleTemplateRenderer();

    @Test
    void acceptsRuntimePebbleAndEvalSyntaxWithoutRendering() {
        assertThatCode(() -> renderer.validateSyntax("""
            {% set pan = eval("#randLong('400000','499999')") %}
            {"pan":"{{ pan }}"}
            """)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBrokenNestedQuotes() {
        assertThatThrownBy(() -> renderer.validateSyntax(
            "{{ eval(\"#base64_encode(\"\" ~ pan ~ \"\")\") }}"))
            .isInstanceOf(TemplateRenderingException.class);
    }

    @Test
    void rejectsInvalidEvalExpression() {
        assertThatThrownBy(() -> renderer.validateSyntax("{{ eval(\"#base64_encode(\") }}"))
            .isInstanceOf(TemplateRenderingException.class);
    }

    @Test
    void rejectsUnclosedPebbleExpression() {
        assertThatThrownBy(() -> renderer.validateSyntax("{{ payload "))
            .isInstanceOf(TemplateRenderingException.class);
    }
}
