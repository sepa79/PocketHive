package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.AuthRuntime;
import io.pockethive.worker.sdk.templating.TemplatingRenderException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpSequenceRequestRendererTest {
    private final WorkerInfo info = new WorkerInfo("http-sequence", "swarm", "worker", null, null);
    private final WorkerContext context = mock(WorkerContext.class);

    @Test
    void rendersAllRequestFieldsFromJourneyPayloadHeadersAndVars() {
        var renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
        var requests = new HttpSequenceRequestRenderer(renderer);
        WorkItem item = WorkItem.text(info, "{}").headers(Map.of(
            "source", "seed", "vars", Map.of("method", "post", "suffix", "tail", "marker", "value"))).build();
        var definition = new HttpTemplateDefinition("default", "A", "HTTP", "{{ vars.method }}",
            "/{{ payload.account }}/{{ ctx.account }}", "{{ payloadAsJson.account }}:{{ headers.source }}:{{ vars.suffix }}",
            Map.of("X-Marker", "{{ vars.marker }}"), null, null);

        try (AuthRuntime runtime = AuthRuntime.inactive(renderer)) {
            var call = requests.render(definition, Map.of("account", "A"), item, context, runtime);
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.path()).isEqualTo("/A/A");
            assertThat(call.body()).isEqualTo("A:seed:tail");
            assertThat(call.headers()).containsExactlyEntriesOf(Map.of("X-Marker", "value"));
        }
    }

    @Test
    void retainsEmptyFieldAndDefaultMethodBehavior() {
        var renderer = new HttpSequenceRequestRenderer((template, variables) -> template);
        var definition = new HttpTemplateDefinition("default", "A", "HTTP", null, "/ready", null, null, null, null);
        try (AuthRuntime runtime = AuthRuntime.inactive((template, variables) -> template)) {
            var call = renderer.render(definition, Map.of(), WorkItem.text(info, "{}").build(), context, runtime);
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/ready");
            assertThat(call.body()).isEmpty();
            assertThat(call.headers()).isEmpty();
        }
    }

    @Test
    void renderingFailureIdentifiesTheFailedFieldAndRetainsItsCause() {
        RuntimeException failure = new IllegalArgumentException("bad expression");
        var renderer = new HttpSequenceRequestRenderer((template, variables) -> { throw failure; });
        var definition = new HttpTemplateDefinition("default", "A", "HTTP", "GET", "{{ bad }}", "", Map.of(), null, null);
        try (AuthRuntime runtime = AuthRuntime.inactive((template, variables) -> template)) {
            assertThatThrownBy(() -> renderer.render(definition, Map.of(), WorkItem.text(info, "{}").build(), context, runtime))
                .isInstanceOf(TemplatingRenderException.class)
                .hasMessage("Failed to render pathTemplate")
                .hasCause(failure);
        }
    }
}
