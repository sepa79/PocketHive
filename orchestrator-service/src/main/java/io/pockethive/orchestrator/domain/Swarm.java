package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.NetworkMode;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public class Swarm {
    private final String id;
    private final String instanceId;
    private final String containerId;
    private final String runId;
    private SwarmLifecycleStatus status;
    private final Instant createdAt;
    private SwarmTemplateMetadata templateMetadata;
    private String sutId;
    private NetworkMode networkMode = NetworkMode.DIRECT;
    private String networkProfileId;
    private volatile JsonNode controllerStatusFull;
    private volatile Instant controllerStatusReceivedAt;

    public Swarm(String id, String instanceId, String containerId, String runId) {
        this.id = id;
        this.instanceId = instanceId;
        this.containerId = containerId;
        this.runId = runId;
        this.status = SwarmLifecycleStatus.NEW;
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

    public SwarmLifecycleStatus getStatus() {
        return status;
    }

    public void transitionTo(SwarmLifecycleStatus next) {
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

    public SwarmTemplateMetadata templateMetadata() {
        return templateMetadata;
    }

    public String templateId() {
        return templateMetadata == null ? null : templateMetadata.templateId();
    }

    public String controllerImage() {
        return templateMetadata == null ? null : templateMetadata.controllerImage();
    }

    public String bundlePath() {
        return templateMetadata == null ? null : templateMetadata.bundlePath();
    }

    public String folderPath() {
        return templateMetadata == null ? null : templateMetadata.folderPath();
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

    public NetworkMode getNetworkMode() {
        return networkMode;
    }

    public void setNetworkMode(NetworkMode networkMode) {
        this.networkMode = NetworkMode.directIfNull(networkMode);
        if (this.networkMode == NetworkMode.DIRECT) {
            this.networkProfileId = null;
        }
    }

    public String getNetworkProfileId() {
        return networkProfileId;
    }

    public void setNetworkProfileId(String networkProfileId) {
        this.networkProfileId = networkProfileId == null || networkProfileId.isBlank()
            ? null
            : networkProfileId.trim();
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
