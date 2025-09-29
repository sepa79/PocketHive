package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Envelope returned to clients initiating control-plane actions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ControlResponse(String correlationId, String idempotencyKey, Watch watch, long timeoutMs) {
    public record Watch(String successTopic, String errorTopic) {}
}

