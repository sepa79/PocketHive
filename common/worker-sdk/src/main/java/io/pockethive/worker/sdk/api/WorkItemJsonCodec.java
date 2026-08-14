package io.pockethive.worker.sdk.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.pockethive.observability.ObservabilityContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Sole JSON Schema, serialization and deserialization boundary for WorkItem envelopes. */
public final class WorkItemJsonCodec {
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .findAndAddModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
    private static final String VERSION = "1";
    private static final WorkItemSchemaValidator SCHEMA_VALIDATOR = WorkItemSchemaValidator.create(MAPPER);

    public byte[] toJson(WorkItem item) {
        try {
            JsonNode node = MAPPER.valueToTree(toEnvelope(item));
            SCHEMA_VALIDATOR.validate(node);
            return MAPPER.writeValueAsBytes(node);
        } catch (WorkItemContractException exception) {
            throw exception;
        } catch (Exception ex) {
            throw new WorkItemContractException("Cannot serialize canonical WorkItem envelope", ex);
        }
    }

    private WorkItemEnvelope toEnvelope(WorkItem item) {
        Objects.requireNonNull(item, "item");
        ObservabilityContext observability = item.observabilityContext().orElse(null);
        List<WorkItemStepEnvelope> steps = new ArrayList<>();
        for (WorkStep step : item.steps()) {
            steps.add(new WorkItemStepEnvelope(
                step.index(),
                step.payload(),
                step.payloadEncoding().wireValue(),
                step.headers()
            ));
        }
        return new WorkItemEnvelope(
            VERSION,
            item.headers(),
            item.messageId(),
            item.contentType(),
            steps,
            observability
        );
    }

    public WorkItem fromJson(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            JsonNode node = MAPPER.readTree(payload);
            if (node == null) {
                throw new WorkItemContractException("WorkItem payload must contain a JSON value");
            }
            SCHEMA_VALIDATOR.validate(node);
            WorkItemEnvelope envelope = MAPPER.treeToValue(node, WorkItemEnvelope.class);
            return fromValidatedEnvelope(envelope);
        } catch (WorkItemContractException exception) {
            throw exception;
        } catch (Exception ex) {
            throw new WorkItemContractException("Cannot deserialize canonical WorkItem envelope", ex);
        }
    }

    private WorkItem fromValidatedEnvelope(WorkItemEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        List<WorkItemStepEnvelope> steps = envelope.steps();
        List<WorkStep> decodedSteps = new ArrayList<>();
        for (WorkItemStepEnvelope step : steps) {
            decodedSteps.add(new WorkStep(
                step.index(),
                step.payload(),
                WorkPayloadEncoding.fromWireValue(step.payloadEncoding()),
                step.headers()
            ));
        }
        return WorkItem.builder()
            .headers(envelope.headers())
            .messageId(envelope.messageId())
            .contentType(envelope.contentType())
            .observabilityContext(envelope.observability())
            .steps(decodedSteps)
            .build();
    }
}
