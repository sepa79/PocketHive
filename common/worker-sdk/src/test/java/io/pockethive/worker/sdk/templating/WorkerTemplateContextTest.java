package io.pockethive.worker.sdk.templating;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorkerTemplateContextTest {
    private static final WorkerInfo INFO = new WorkerInfo("templating", "swarm", "instance", null, null);

    @ParameterizedTest
    @MethodSource("jsonValues")
    void textContextKeepsAllExistingJsonValueShapes(String payload, Object expected) {
        WorkItem item = WorkItem.text(INFO, payload).build();

        Map<String, Object> context = WorkerTemplateContext.textPayload(item, payload);

        assertThat(context).containsEntry("payload", payload).containsEntry("payloadAsJson", expected)
            .containsEntry("headers", item.headers()).containsEntry("workItem", item)
            .doesNotContainKeys("vars", "payloadAsXML");
    }

    static Stream<Arguments> jsonValues() {
        return Stream.of(
            Arguments.of("{\"id\":\"000123\"} \n", Map.of("id", "000123")),
            Arguments.of("[\"000123\",false]", List.of("000123", false)),
            Arguments.of("\"000123\"", "000123"),
            Arguments.of("false", false),
            Arguments.of("7", 7),
            Arguments.of("null", null));
    }

    @Test
    void objectContextRetainsItsExistingAliasesWithoutCreatingPayloadAsJson() {
        WorkItem item = WorkItem.text(INFO, "{\"id\":\"000123\"}")
            .header("vars", Map.of("code", "000007")).build();

        Map<String, Object> context = WorkerTemplateContext.objectPayload(item);

        assertThat(context).containsEntry("vars", item.headers().get("vars"))
            .containsEntry("headers", item.headers()).containsEntry("workItem", item)
            .doesNotContainKeys("payloadAsJson", "payloadAsXML");
        assertThat(context.get("payload")).isEqualTo(Map.of("id", "000123"));
        assertThat(item.payload()).isEqualTo("{\"id\":\"000123\"}");
    }
}
