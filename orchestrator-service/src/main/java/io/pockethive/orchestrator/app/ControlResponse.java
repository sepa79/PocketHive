package io.pockethive.orchestrator.app;

import java.util.List;
import java.util.Objects;

/**
 * Envelope returned to clients initiating control-plane actions.
 */
public record ControlResponse(String correlationId, String idempotencyKey, Watch watch, long timeoutMs) {
    public record Watch(String successTopic, List<String> errorTopics) {
        public Watch {
            successTopic = Objects.requireNonNull(successTopic, "successTopic");
            errorTopics = List.copyOf(Objects.requireNonNull(errorTopics, "errorTopics"));
            if (successTopic.isBlank() || errorTopics.isEmpty()) {
                throw new IllegalArgumentException("Watch requires a success topic and at least one error topic");
            }
        }
    }
}
