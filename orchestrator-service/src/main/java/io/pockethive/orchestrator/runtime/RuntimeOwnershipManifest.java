package io.pockethive.orchestrator.runtime;

import java.time.Instant;
import java.util.List;

public record RuntimeOwnershipManifest(
    String swarmId,
    String runId,
    String templateId,
    String computeAdapter,
    Instant createdAt,
    List<RuntimeObject> runtimeObjects,
    RabbitResources rabbit) {

    public RuntimeOwnershipManifest {
        runtimeObjects = runtimeObjects == null ? List.of() : List.copyOf(runtimeObjects);
        rabbit = rabbit == null ? new RabbitResources(List.of(), List.of(), List.of()) : rabbit;
    }

    public record RuntimeObject(
        String runtimeId,
        String runtimeType,
        String resourceKind,
        String role,
        String instance,
        String image) {
    }

    public record RabbitResources(
        List<String> controlQueues,
        List<String> workQueues,
        List<String> exchanges) {
        public RabbitResources {
            controlQueues = controlQueues == null ? List.of() : List.copyOf(controlQueues);
            workQueues = workQueues == null ? List.of() : List.copyOf(workQueues);
            exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
        }
    }
}
