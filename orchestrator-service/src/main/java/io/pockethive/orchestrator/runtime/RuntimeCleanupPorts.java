package io.pockethive.orchestrator.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RuntimeCleanupPorts {
    public static final String RUNTIME_TYPE_CONTAINER = "container";
    public static final String RUNTIME_TYPE_SERVICE = "service";

    private RuntimeCleanupPorts() {
    }

    public interface RuntimeOwnershipManifestStore {
        void save(RuntimeOwnershipManifest manifest);

        Optional<RuntimeOwnershipManifest> find(String swarmId, String runId);

        Optional<RuntimeOwnershipManifest> findLatest(String swarmId);
    }

    public interface ComputeRuntimeInventoryPort {
        List<ComputeRuntimeResource> list(String computeAdapter);
    }

    public interface ComputeRuntimeRemovalPort {
        void removeContainer(String runtimeId);

        void removeService(String runtimeId);
    }

    public interface RabbitTopologyPort {
        Optional<RabbitQueueResource> queue(String name);

        Optional<RabbitExchangeResource> exchange(String name);

        void deleteQueue(String name);

        void deleteExchange(String name);
    }

    public record ComputeRuntimeResource(
        String runtimeId,
        String runtimeType,
        String name,
        String image,
        String state,
        String createdAt,
        String startedAt,
        String finishedAt,
        Map<String, String> labels) {
        public ComputeRuntimeResource(String runtimeId,
                                      String runtimeType,
                                      String name,
                                      String image,
                                      String state,
                                      Map<String, String> labels) {
            this(runtimeId, runtimeType, name, image, state, null, null, null, labels);
        }

        public ComputeRuntimeResource {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }

    public record RabbitQueueResource(String name, long depth, int consumers) {
    }

    public record RabbitExchangeResource(String name) {
    }
}
