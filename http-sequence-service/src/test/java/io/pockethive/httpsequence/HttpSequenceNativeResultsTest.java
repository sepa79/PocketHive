package io.pockethive.httpsequence;

import io.pockethive.requesttemplates.HttpSequenceExtract;
import io.pockethive.requesttemplates.HttpSequenceSetValue;
import io.pockethive.requesttemplates.HttpSequenceStep;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class HttpSequenceNativeResultsTest {
    private final HttpSequenceNativeResults results = new HttpSequenceNativeResults(
        new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));

    @Test
    void projectsNativeStatusHeadersAndTypedJsonWithoutWritingState() {
        var step = step(List.of(
            new HttpSequenceExtract(null, null, true, "status", true),
            new HttpSequenceExtract(null, "Location", false, "location", true),
            new HttpSequenceExtract("/record", null, false, "record", true),
            new HttpSequenceExtract("/empty", null, false, "empty", true)));
        var response = new HttpCallResult(201, Map.of("location", List.of("/created")),
            "{\"record\":{\"id\":\"0001\",\"count\":2,\"active\":true},\"empty\":\"\"}", null);

        assertThat(results.extract(step, response)).containsExactly(
            new HttpSequencePayloadUpdate("status", 201), new HttpSequencePayloadUpdate("location", "/created"),
            new HttpSequencePayloadUpdate("record", Map.of("id", "0001", "count", 2, "active", true)),
            new HttpSequencePayloadUpdate("empty", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":\"first\",\"id\":\"second\"}", "{\"id\":\"first\"} {}"})
    void optionalNativeExtractionDoesNotAcceptTruncatedOrDuplicateJson(String body) {
        var step = step(List.of(new HttpSequenceExtract("/id", null, false, "id", false)));
        assertThat(results.extract(step, new HttpCallResult(200, Map.of(), body, null))).isEmpty();
    }

    @Test
    void stringSelectionRemainsAString() {
        var step = step(List.of(new HttpSequenceExtract("/value", null, false, "text", true)));
        assertThat(results.extract(step, new HttpCallResult(200, Map.of(), "{\"value\":\"0001\"}", null)))
            .containsExactly(new HttpSequencePayloadUpdate("text", "0001"));
    }

    @Test
    void requiredMissingAndInvalidPointersFailExplicitly() {
        var response = new HttpCallResult(200, Map.of(), "{}", null);
        assertThatThrownBy(() -> results.extract(step(List.of(
            new HttpSequenceExtract("/missing", null, false, "id", true))), response))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Required extract missing");
        assertThatThrownBy(() -> results.extract(step(List.of(
            new HttpSequenceExtract("/~2", null, false, "id", false))), response))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsNativeSetterAliasesAndOptionalJsonProjection() {
        var info = new WorkerInfo("http-sequence", "swarm", "worker", null, null);
        var item = WorkItem.text(info, "{}").headers(Map.of("vars", Map.of("suffix", "tail"))).build();
        Map<String, Object> payload = Map.of("id", "0001");
        var setter = new HttpSequenceSetValue("result", """
            {"id":"{{ payload.id }}:{{ payloadAsJson.id }}:{{ ctx.id }}:{{ vars.suffix }}"}
            """, true);
        assertThat(results.renderSetter(setter, payload, item)).isEqualTo(Map.of("id", "0001:0001:0001:tail"));
        assertThat(payload).containsOnlyKeys("id");
        var invalid = new HttpSequenceSetValue("result", "{\"id\":1} {}", true);
        assertThat(results.renderSetter(invalid, payload, item)).isEqualTo("{\"id\":1} {}");
    }

    private HttpSequenceStep step(List<HttpSequenceExtract> extracts) {
        return new HttpSequenceStep("native", "native", null, false, null, extracts, List.of());
    }
}
