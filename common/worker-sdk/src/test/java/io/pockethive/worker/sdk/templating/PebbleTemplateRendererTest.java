package io.pockethive.worker.sdk.templating;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PebbleTemplateRendererTest {

    private final TemplateRenderer renderer = new PebbleTemplateRenderer();

    @Test
    void rendersStaticTemplate() {
        String result = renderer.render("hello", Map.of());
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void rendersTemplateWithVariables() {
        String template = "Hello {{ name }}, count={{ count }}";
        String result = renderer.render(template, Map.of("name", "PocketHive", "count", 3));
        assertThat(result).isEqualTo("Hello PocketHive, count=3");
    }
}

