package io.pockethive.worker.sdk.templating;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTemplateRendererTest {

    private final TemplateRenderer renderer = new PebbleTemplateRenderer();
    private final MessageTemplateRenderer messageRenderer = new MessageTemplateRenderer(renderer);

    @Test
    void keepsPayloadAsRawStringAndExposesParsedJsonUnderPayloadAsJson() {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), "{\"col0\":\"value0\"}").build();
        MessageTemplate template = MessageTemplate.builder()
            .pathTemplate("/api/{{ payloadAsJson.col0 }}")
            .methodTemplate("POST")
            .bodyTemplate("json={{ payloadAsJson.col0 }}|raw={{ payload }}")
            .build();

        MessageTemplateRenderer.RenderedMessage rendered = messageRenderer.render(template, seed);

        assertThat(rendered.path()).isEqualTo("/api/value0");
        assertThat(rendered.body()).isEqualTo("json=value0|raw={\"col0\":\"value0\"}");
    }

    @Test
    void payloadAsJsonIsNullWhenPayloadIsNotJson() {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), "not-json").build();
        MessageTemplate template = MessageTemplate.builder()
            .pathTemplate("{% if payloadAsJson %}/ok{% else %}/missing{% endif %}")
            .methodTemplate("POST")
            .bodyTemplate("{% if payloadAsJson %}{{ payloadAsJson }}{% else %}null{% endif %}|{{ payload }}")
            .build();

        MessageTemplateRenderer.RenderedMessage rendered = messageRenderer.render(template, seed);

        assertThat(rendered.path()).isEqualTo("/missing");
        assertThat(rendered.body()).isEqualTo("null|not-json");
    }

    @Test
    void exposesVarsFromHeadersToPebbleAndEval() {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), "hello")
            .header("vars", Map.of("loopCount", 10, "enableFoo", true))
            .build();
        MessageTemplate template = MessageTemplate.builder()
            .pathTemplate("/{{ vars.loopCount }}")
            .methodTemplate("POST")
            .bodyTemplate("flag={{ eval(\"vars.enableFoo ? 'Y' : 'N'\") }}")
            .build();

        MessageTemplateRenderer.RenderedMessage rendered = messageRenderer.render(template, seed);

        assertThat(rendered.path()).isEqualTo("/10");
        assertThat(rendered.body()).isEqualTo("flag=Y");
    }
}
