package io.pockethive.worker.sdk.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.observability.ObservabilityContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorkItemJsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String VERSION = "1";

    public byte[] toJson(WorkItem item) {
        WorkItemEnvelope envelope = toEnvelope(item);
        try {
            return MAPPER.writeValueAsBytes(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize WorkItem envelope", ex);
        }
    }

    public WorkItemEnvelope toEnvelope(WorkItem item) {
        Objects.requireNonNull(item, "item");
        ObservabilityContext observability = item.observabilityContext()
            .orElseThrow(() -> new IllegalStateException("WorkItem must include observability context"));
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
            item.payload(),
            item.payloadEncoding().wireValue(),
            item.headers(),
            item.messageId(),
            item.contentType(),
            steps,
            observability
        );
    }

    public WorkItem fromJson(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        WorkItemEnvelope envelope;
        try {
            envelope = MAPPER.readValue(payload, WorkItemEnvelope.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize WorkItem envelope", ex);
        }
        return fromEnvelope(envelope);
    }

    public WorkItem fromEnvelope(WorkItemEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (!VERSION.equals(envelope.version())) {
            throw new IllegalArgumentException("Unsupported WorkItem envelope version: " + envelope.version());
        }
        List<WorkItemStepEnvelope> steps = envelope.steps();
        WorkItemStepEnvelope last = steps.get(steps.size() - 1);
        if (!Objects.equals(envelope.payload(), last.payload())
            || !Objects.equals(envelope.payloadEncoding(), last.payloadEncoding())) {
            throw new IllegalArgumentException("WorkItem envelope payload does not match last step");
        }
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
