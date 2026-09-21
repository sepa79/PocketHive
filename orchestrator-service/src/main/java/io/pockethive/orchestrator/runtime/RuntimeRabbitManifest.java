package io.pockethive.orchestrator.runtime;

import java.util.List;

/**
 * Responsibility: carry the immutable current Rabbit resource projection of the runtime ownership manifest.
 * Must not: resolve names, access resources or decide lifecycle/cleanup outcomes.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
public record RuntimeRabbitManifest(List<String> controlQueues, List<String> workQueues, List<String> exchanges) {
    public RuntimeRabbitManifest {
        controlQueues = controlQueues == null ? List.of() : List.copyOf(controlQueues);
        workQueues = workQueues == null ? List.of() : List.copyOf(workQueues);
        exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
    }
}
