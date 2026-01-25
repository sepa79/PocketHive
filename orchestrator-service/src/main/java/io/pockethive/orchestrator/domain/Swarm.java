package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.Bee;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class Swarm {
    private final String id;
    private final String instanceId;
    private final String containerId;
    private final String runId;
    private SwarmStatus status;
    private final Instant createdAt;
    private SwarmTemplateMetadata templateMetadata;
    private String sutId;
    private volatile JsonNode controllerStatusFull;
    private volatile Instant controllerStatusReceivedAt;

    public Swarm(String id, String instanceId, String containerId, String runId) {
        this.id = id;
        this.instanceId = instanceId;
        this.containerId = containerId;
        this.runId = runId;
        this.status = SwarmStatus.NEW;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getContainerId() {
        return containerId;
    }

    public String getRunId() {
        return runId;
    }

    public SwarmStatus getStatus() {
        return status;
    }

    public void transitionTo(SwarmStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Cannot transition from " + status + " to " + next);
        }
        this.status = next;
    }

    public void attachTemplate(SwarmTemplateMetadata metadata) {
        this.templateMetadata = metadata;
    }

    public void clearTemplate() {
        this.templateMetadata = null;
    }

    public Optional<SwarmTemplateMetadata> templateMetadata() {
        return Optional.ofNullable(templateMetadata);
    }

    public Optional<String> templateId() {
        return templateMetadata().map(SwarmTemplateMetadata::templateId);
    }

    public Optional<String> controllerImage() {
        return templateMetadata().map(SwarmTemplateMetadata::controllerImage);
    }

    public List<Bee> bees() {
        return templateMetadata == null ? List.of() : templateMetadata.bees();
    }

    public String getSutId() {
        return sutId;
    }

    public void setSutId(String sutId) {
        this.sutId = sutId;
    }

    public synchronized void updateControllerStatusFull(JsonNode envelope, Instant receivedAt) {
        this.controllerStatusFull = envelope == null ? null : envelope.deepCopy();
        this.controllerStatusReceivedAt = receivedAt;
    }

    public JsonNode getControllerStatusFull() {
        return controllerStatusFull;
    }

    public Instant getControllerStatusReceivedAt() {
        return controllerStatusReceivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
