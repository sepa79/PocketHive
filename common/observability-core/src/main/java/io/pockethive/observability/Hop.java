package io.pockethive.observability;

import java.time.Instant;

/**
 * Responsibility: define the Hop contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-OBS-CONTEXT — docs/architecture/runtime-responsibilities.md#resp-obs-context.
 */
public class Hop {
    private String service;
    private String instance;
    private Instant receivedAt;
    private Instant processedAt;

    public Hop() {
    }

    public Hop(String service, String instance, Instant receivedAt, Instant processedAt) {
        this.service = service;
        this.instance = instance;
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
