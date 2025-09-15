package io.pockethive.orchestrator.app;

/**
 * Envelope returned to clients initiating control-plane actions.
 */
public record ControlResponse(String correlationId, String idempotencyKey, Watch watch, long timeoutMs) {
    public record Watch(String successTopic, String errorTopic) {}
}

