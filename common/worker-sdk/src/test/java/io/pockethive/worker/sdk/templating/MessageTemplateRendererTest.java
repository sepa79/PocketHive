package io.pockethive.worker.sdk.templating;

import io.pockethive.templating.api.DisabledSequenceAccess;

import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.worker.sdk.testing.AuthoredTemplateFixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MessageTemplateRendererTest {

    private final TemplateRenderer renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
    private final MessageTemplateRenderer messageRenderer = new MessageTemplateRenderer(renderer);

    @Test
    void keepsPayloadAsRawStringAndExposesParsedJsonUnderPayloadAsJson() {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), "{\"col0\":\"value0\"}").build();
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.HTTP)
            .pathTemplate("/api/{{ payloadAsJson.col0 }}")
            .methodTemplate("POST")
            .bodyTemplate("json={{ payloadAsJson.col0 }}|raw={{ payload }}")
            .build();

        RenderedMessage rendered = messageRenderer.render(template, seed);

        assertThat(rendered.path()).isEqualTo("/api/value0");
        assertThat(rendered.body()).isEqualTo("json=value0|raw={\"col0\":\"value0\"}");
    }

    @Test
    void payloadAsJsonIsNullWhenPayloadIsNotJson() {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), "not-json").build();
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.HTTP)
            .pathTemplate("{% if payloadAsJson %}/ok{% else %}/missing{% endif %}")
            .methodTemplate("POST")
            .bodyTemplate("{% if payloadAsJson %}{{ payloadAsJson }}{% else %}null{% endif %}|{{ payload }}")
            .build();

        RenderedMessage rendered = messageRenderer.render(template, seed);

        assertThat(rendered.path()).isEqualTo("/missing");
        assertThat(rendered.body()).isEqualTo("null|not-json");
    }

    @Test
    void exposesVarsFromHeadersToPebbleAndEval() {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), "hello")
            .header("vars", Map.of("loopCount", 10, "enableFoo", true))
            .build();
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.HTTP)
            .pathTemplate("/{{ vars.loopCount }}")
            .methodTemplate("POST")
            .bodyTemplate("flag={{ eval(\"vars.enableFoo ? 'Y' : 'N'\") }}")
            .build();

        RenderedMessage rendered = messageRenderer.render(template, seed);

        assertThat(rendered.path()).isEqualTo("/10");
        assertThat(rendered.body()).isEqualTo("flag=Y");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"id\":\"000123\",\"id\":\"000456\"}",
        "{\"nested\":{\"id\":1,\"id\":2}}",
        "{\"id\":\"000123\"} {}",
        "{\"id\":\"000123\"} trailing",
        "{\"id\":\"000123\"",
        "<card id=\"000123\"/>",
        "plain 000123", "", "  ", "null"
    })
    void invalidOptionalJsonDoesNotBecomeAPreparsedDocumentAndRawPayloadIsUnchanged(String payload) {
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), payload).build();
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.HTTP)
            .bodyTemplate("{% if payloadAsJson is null %}absent{% else %}projected{% endif %}|{{ payload }}")
            .build();

        assertThat(messageRenderer.render(template, seed).body()).isEqualTo("absent|" + payload);
        assertThat(seed.payload()).isEqualTo(payload);
    }

    @ParameterizedTest
    @MethodSource("performanceDocuments")
    void rendersAnExistingTcpTemplateWithoutChangingLeadingZerosOrHeaderAliases(
        String payload, String expectedBody, String expectedTransaction, String expectedPriority
    ) throws Exception {
        var definition = AuthoredTemplateFixtures.read("scenarios/tcp/tcp-performance-demo/templates/tcp/default/perf-test.yaml");
        Map<String, String> headerTemplates = new java.util.LinkedHashMap<>();
        definition.path("headersTemplate").fields().forEachRemaining(entry ->
            headerTemplates.put(entry.getKey(), entry.getValue().textValue()));
        MessageTemplate template = MessageTemplate.builder()
            .bodyType(MessageBodyType.SIMPLE)
            .bodyTemplate(definition.path("bodyTemplate").textValue())
            .headerTemplates(headerTemplates)
            .build();
        WorkItem seed = WorkItem.text(new WorkerInfo("templating", "swarm", "instance", null, null), payload)
            .header("x-ph-phase", "TOP")
            .build();

        RenderedMessage rendered = messageRenderer.render(template, seed);

        assertThat(rendered.body()).isEqualTo(expectedBody);
        assertThat(rendered.headers()).containsEntry("x-ph-txn-id", expectedTransaction)
            .containsEntry("x-ph-priority", expectedPriority).containsEntry("x-ph-phase", "TOP");
    }

    static Stream<Arguments> performanceDocuments() {
        return Stream.of(
            Arguments.of("{\"priority\":\"007\",\"txnId\":\"000123\",\"payload\":\"000045\",\"timestamp\":\"20261020\"}",
                "PERF|007|000123|000045|20261020", "000123", "007"),
            Arguments.of("{\"txnId\":\"000123\",\"txnId\":\"000456\"}", "PERF||||", "", ""),
            Arguments.of("{\"txnId\":\"000123\"} {}", "PERF||||", "", ""));
    }
}
