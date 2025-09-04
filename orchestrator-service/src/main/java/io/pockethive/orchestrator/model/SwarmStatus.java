package io.pockethive.orchestrator.model;

import java.time.LocalDateTime;
import java.util.List;

public class SwarmStatus {
    public enum Status {
        CREATING, RUNNING, STOPPING, STOPPED, FAILED
    }

    private String swarmId;
    private Status status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<String> containerIds;
    private String errorMessage;

    // Constructors
    public SwarmStatus() {}

    public SwarmStatus(String swarmId, Status status) {
        this.swarmId = swarmId;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and setters
    public String getSwarmId() { return swarmId; }
    public void setSwarmId(String swarmId) { this.swarmId = swarmId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { 
        this.status = status; 
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<String> getContainerIds() { return containerIds; }
    public void setContainerIds(List<String> containerIds) { this.containerIds = containerIds; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}