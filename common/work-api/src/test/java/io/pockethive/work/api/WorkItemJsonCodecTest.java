package io.pockethive.work.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkItemJsonCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final WorkItemJsonCodec codec = new WorkItemJsonCodec();

    @Test
    void serializesAndDeserializesOnlyCanonicalEnvelope() throws Exception {
        byte[] payload = codec.toJson(validItem());
        JsonNode envelope = MAPPER.readTree(payload);

        assertThat(envelope.at("/observability/hops/0/receivedAt").isTextual()).isTrue();
        assertThat(codec.fromJson(payload).payload()).isEqualTo("payload");
    }

    @ParameterizedTest
    @ValueSource(strings = {" {}", " trailing"})
    void rejectsTrailingInputAfterAValidEnvelope(String suffix) {
        String envelope = new String(codec.toJson(validItem()), StandardCharsets.UTF_8);

        assertWireRejected(envelope + suffix);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/version",
        "/steps/0/payload",
        "/steps/0/headers/" + WorkItem.STEP_SERVICE_HEADER,
        "/observability/traceId",
        "/headers/business/customer"
    })
    void rejectsDuplicatePropertiesAtEveryEnvelopeNestingLevel(String pointer) throws Exception {
        WorkItem original = WorkItem.text(workerInfo(), "payload")
            .header("business", Map.of("customer", "customer-1"))
            .observabilityContext(ObservabilityContextUtil.init("generator", "generator-1", "swarm-a"))
            .build();
        String envelope = new String(codec.toJson(original), StandardCharsets.UTF_8);
        JsonNode value = MAPPER.readTree(envelope).at(pointer);
        String key = MAPPER.writeValueAsString(pointer.substring(pointer.lastIndexOf('/') + 1));
        String property = key + ":" + MAPPER.writeValueAsString(value);
        String invalid = envelope.replace(property, key + ":\"discarded\"," + property);

        assertThat(invalid).isNotEqualTo(envelope);
        assertWireRejected(invalid);
    }

    @Test
    void acceptsTrailingWhitespace() throws Exception {
        WorkItem original = validItem();
        String envelope = new String(codec.toJson(original), StandardCharsets.UTF_8);

        WorkItem decoded = codec.fromJson((envelope + " \t\r\n").getBytes(StandardCharsets.UTF_8));

        assertSameEnvelope(original, decoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"ok\":true}",
        "<result>ok</result>",
        "{broken",
        "plain text",
        "",
        "{\"key\":1,\"key\":2}",
        "{} trailing"
    })
    void preservesOpaqueTextPayloads(String payload) throws Exception {
        WorkItem original = validItem(payload);

        WorkItem decoded = codec.fromJson(codec.toJson(original));

        assertThat(decoded.payload()).isEqualTo(payload);
        assertThat(decoded.payloadEncoding()).isEqualTo(WorkPayloadEncoding.UTF_8);
        assertSameEnvelope(original, decoded);
    }

    @Test
    void preservesBinaryPayloadAndEnvelopeMetadata() throws Exception {
        WorkerInfo info = workerInfo();
        byte[] bytes = {0, 1, 31, 127, (byte) 128, (byte) 255};
        WorkItem original = WorkItem.binary(info, bytes)
            .messageId("binary-message")
            .contentType("application/octet-stream")
            .header("business", Map.of("customer", "customer-1"))
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
            .build();

        WorkItem decoded = codec.fromJson(codec.toJson(original));

        assertThat(decoded.body()).containsExactly(bytes);
        assertThat(decoded.payloadEncoding()).isEqualTo(WorkPayloadEncoding.BASE64);
        assertSameEnvelope(original, decoded);
    }

    @Test
    void preservesOpaquePayloadsAndHeadersAcrossHistorySteps() throws Exception {
        WorkItem original = validItem("<first/>")
            .addStep(workerInfo(), "{broken", Map.of("business", Map.of("customer", "customer-1")))
            .addStepPayload(workerInfo(), "{\"key\":1,\"key\":2}");

        WorkItem decoded = codec.fromJson(codec.toJson(original));

        assertThat(decoded.steps()).extracting(WorkStep::payload)
            .containsExactly("<first/>", "{broken", "{\"key\":1,\"key\":2}");
        assertSameEnvelope(original, decoded);
    }

    @Test
    void rejectsAnObjectInsteadOfAnOpaqueStepPayloadString() throws Exception {
        ObjectNode invalid = validEnvelope();
        ((ObjectNode) invalid.at("/steps/0")).set("payload", MAPPER.createObjectNode().put("ok", true));

        assertSchemaRejected(invalid);
    }

    @Test
    void rejectsEveryMissingRequiredTopLevelField() throws Exception {
        for (String field : List.of("version", "headers", "steps", "observability")) {
            ObjectNode invalid = validEnvelope();
            invalid.remove(field);

            assertSchemaRejected(invalid);
        }
    }

    @Test
    void rejectsMissingRequiredNestedFields() throws Exception {
        ObjectNode missingTrackingHeader = validEnvelope();
        ((ObjectNode) missingTrackingHeader.at("/steps/0/headers")).remove(WorkItem.STEP_SERVICE_HEADER);
        assertSchemaRejected(missingTrackingHeader);

        ObjectNode missingTraceId = validEnvelope();
        ((ObjectNode) missingTraceId.path("observability")).remove("traceId");
        assertSchemaRejected(missingTraceId);

        ObjectNode missingProcessedAt = validEnvelope();
        ((ObjectNode) missingProcessedAt.at("/observability/hops/0")).remove("processedAt");
        assertSchemaRejected(missingProcessedAt);
    }

    @Test
    void rejectsWrongTypesEnumsAndAdditionalFields() throws Exception {
        ObjectNode wrongIndexType = validEnvelope();
        ((ObjectNode) wrongIndexType.at("/steps/0")).put("index", "zero");
        assertSchemaRejected(wrongIndexType);

        ObjectNode wrongEncoding = validEnvelope();
        ((ObjectNode) wrongEncoding.at("/steps/0")).put("payloadEncoding", "utf-16");
        assertSchemaRejected(wrongEncoding);

        ObjectNode additionalRootField = validEnvelope();
        additionalRootField.put("legacyPayload", "not permitted");
        assertSchemaRejected(additionalRootField);
    }

    @Test
    void rejectsNullExtraStepHeadersAtTheSchemaBoundary() throws Exception {
        ObjectNode nullExtraHeader = validEnvelope();
        ((ObjectNode) nullExtraHeader.at("/steps/0/headers")).putNull("x-extra");

        assertSchemaRejected(nullExtraHeader);
    }

    @Test
    void rejectsOutboundObservabilityThatDoesNotSatisfyTheSchema() {
        WorkItem item = WorkItem.text(workerInfo(), "payload")
            .observabilityContext(new ObservabilityContext())
            .build();

        assertThatThrownBy(() -> codec.toJson(item))
            .isInstanceOf(WorkItemContractException.class)
            .hasMessageStartingWith("WorkItem schema validation failed:");
    }

    @Test
    void rejectsOutboundMissingStepTrackingHeaderThroughTheSchema() {
        WorkItem item = validItem().addStepHeader(WorkItem.STEP_SERVICE_HEADER, null);

        assertThatThrownBy(() -> codec.toJson(item))
            .isInstanceOf(WorkItemContractException.class)
            .hasMessageStartingWith("WorkItem schema validation failed:");
    }

    private ObjectNode validEnvelope() throws Exception {
        return (ObjectNode) MAPPER.readTree(codec.toJson(validItem()));
    }

    private void assertWireRejected(String invalid) {
        assertThatThrownBy(() -> codec.fromJson(invalid.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(WorkItemContractException.class)
            .hasMessage("Cannot deserialize canonical WorkItem envelope");
    }

    private void assertSameEnvelope(WorkItem original, WorkItem decoded) throws Exception {
        assertThat(MAPPER.readTree(codec.toJson(decoded))).isEqualTo(MAPPER.readTree(codec.toJson(original)));
    }

    private void assertSchemaRejected(JsonNode invalid) throws Exception {
        assertThatThrownBy(() -> codec.fromJson(MAPPER.writeValueAsBytes(invalid)))
            .isInstanceOf(WorkItemContractException.class)
            .hasMessageStartingWith("WorkItem schema validation failed:");
    }

    private static WorkItem validItem() {
        return validItem("payload");
    }

    private static WorkItem validItem(String payload) {
        WorkerInfo info = workerInfo();
        return WorkItem.text(info, payload)
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
            .build();
    }

    private static WorkerInfo workerInfo() {
        return new WorkerInfo("generator", "swarm-a", "generator-1", null, null);
    }
}
