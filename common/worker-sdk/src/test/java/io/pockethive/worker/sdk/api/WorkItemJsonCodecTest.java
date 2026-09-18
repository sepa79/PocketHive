package io.pockethive.worker.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    private void assertSchemaRejected(JsonNode invalid) throws Exception {
        assertThatThrownBy(() -> codec.fromJson(MAPPER.writeValueAsBytes(invalid)))
            .isInstanceOf(WorkItemContractException.class)
            .hasMessageStartingWith("WorkItem schema validation failed:");
    }

    private static WorkItem validItem() {
        WorkerInfo info = workerInfo();
        return WorkItem.text(info, "payload")
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
            .build();
    }

    private static WorkerInfo workerInfo() {
        return new WorkerInfo("generator", "swarm-a", "generator-1", null, null);
    }
}
