package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.SwarmStartupArtifactReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeIntent;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.WorkloadIntent;
import io.pockethive.swarm.model.lifecycle.WorkloadState;

public class Swarm {
    private final String id;
    private final String instanceId;
    private final String containerId;
    private final String runId;
    private final Instant createdAt;
    private SwarmTemplateMetadata templateMetadata;
    private SwarmStartupArtifactReference startupArtifact;
    private String sutId;
    private NetworkMode networkMode = NetworkMode.DIRECT;
    private String networkProfileId;
    private volatile JsonNode controllerStatusFull;
    private volatile Instant controllerStatusReceivedAt;
    private volatile RuntimeIntent runtimeIntent = RuntimeIntent.PRESENT;
    private volatile WorkloadIntent workloadIntent = WorkloadIntent.STOPPED;
    private volatile ControllerState controllerState = ControllerState.PROVISIONING;
    private volatile WorkloadState workloadState = WorkloadState.UNAVAILABLE;
    private volatile Health health = Health.UNKNOWN;
    private volatile RuntimeResourceState runtimeResourceState = RuntimeResourceState.PRESENT;
    private volatile Map<String, Object> observation = Map.of();

    public Swarm(String id, String instanceId, String containerId, String runId) {
        this.id = id;
        this.instanceId = instanceId;
        this.containerId = containerId;
        this.runId = runId;
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

    public void attachTemplate(SwarmTemplateMetadata metadata) {
        this.templateMetadata = metadata;
    }

    public void clearTemplate() {
        this.templateMetadata = null;
    }

    public SwarmTemplateMetadata templateMetadata() {
        return templateMetadata;
    }

    public void attachStartupArtifact(SwarmStartupArtifactReference reference) {
        this.startupArtifact = java.util.Objects.requireNonNull(reference, "reference");
    }

    public SwarmStartupArtifactReference startupArtifact() {
        return startupArtifact;
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

    public RuntimeIntent getRuntimeIntent() {
        return runtimeIntent;
    }

    public WorkloadIntent getWorkloadIntent() {
        return workloadIntent;
    }

    public synchronized void requestRuntime(RuntimeIntent runtimeIntent) {
        this.runtimeIntent = java.util.Objects.requireNonNull(runtimeIntent, "runtimeIntent");
        if (runtimeIntent == RuntimeIntent.ABSENT) {
            this.workloadIntent = WorkloadIntent.STOPPED;
            this.runtimeResourceState = RuntimeResourceState.REMOVING;
        }
    }

    public void requestWorkload(WorkloadIntent workloadIntent) {
        this.workloadIntent = java.util.Objects.requireNonNull(workloadIntent, "workloadIntent");
    }

    public synchronized void updateObservation(
        ControllerState controllerState,
        WorkloadState workloadState,
        Health health,
        RuntimeResourceState runtimeResourceState,
        Map<String, Object> observation,
        Instant observedAt) {
        this.controllerState = java.util.Objects.requireNonNull(controllerState, "controllerState");
        this.workloadState = java.util.Objects.requireNonNull(workloadState, "workloadState");
        this.health = java.util.Objects.requireNonNull(health, "health");
        this.runtimeResourceState = java.util.Objects.requireNonNull(runtimeResourceState, "runtimeResourceState");
        this.observation = observation == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(observation));
        this.controllerStatusReceivedAt = java.util.Objects.requireNonNull(observedAt, "observedAt");
    }

    public synchronized void markObservationStale() {
        this.controllerState = ControllerState.UNKNOWN;
        this.workloadState = WorkloadState.UNKNOWN;
        this.health = Health.UNKNOWN;
        this.runtimeResourceState = RuntimeResourceState.UNKNOWN;
    }

    public ControllerState getControllerState() { return controllerState; }
    public WorkloadState getWorkloadState() { return workloadState; }
    public Health getHealth() { return health; }
    public RuntimeResourceState getRuntimeResourceState() { return runtimeResourceState; }
    public Map<String, Object> getObservation() { return observation; }
}
