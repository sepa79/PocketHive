package io.pockethive.orchestrator.runtime;

import io.pockethive.docker.DockerRuntimeClient;
import io.pockethive.docker.DockerRuntimeKind;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeRemovalPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.manager.ports.ComputeRuntimeDebugPort;
import io.pockethive.manager.runtime.RuntimeInspection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Responsibility: project Docker runtime operations onto Orchestrator runtime ports.
 * Must not: issue Docker commands, select compute mode or decide cleanup eligibility.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
@Component
public class DockerRuntimeAdapter implements ComputeRuntimeInventoryPort, ComputeRuntimeRemovalPort, ComputeRuntimeDebugPort {
    private final DockerRuntimeClient runtime;
    private final ComputeAdapter computeAdapter;

    public DockerRuntimeAdapter(DockerRuntimeClient runtime, ComputeAdapter computeAdapter) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
    }

    @Override
    public List<ComputeRuntimeResource> list() {
        return runtime.list(kind()).stream().map(resource -> new ComputeRuntimeResource(
            resource.runtimeId(),
            switch (resource.kind()) {
                case CONTAINER -> RuntimeCleanupPorts.RUNTIME_TYPE_CONTAINER;
                case SERVICE -> RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE;
            },
            resource.name(), resource.image(), resource.state(), resource.createdAt(),
            resource.startedAt(), resource.finishedAt(), resource.labels())).toList();
    }

    @Override
    public void removeContainer(String runtimeId) {
        runtime.removeContainer(runtimeId);
    }

    @Override
    public void removeService(String runtimeId) {
        runtime.removeService(runtimeId);
    }

    @Override
    public RuntimeInspection inspect(String runtimeId) {
        return runtime.inspect(kind(), runtimeId);
    }

    @Override
    public String logs(String runtimeId, int tailLines, Integer sinceEpochSeconds) {
        return runtime.logs(kind(), runtimeId, tailLines, sinceEpochSeconds);
    }

    private DockerRuntimeKind kind() {
        var type = computeAdapter.type();
        if (type == null) {
            throw unsupportedComputeAdapter();
        }
        return switch (type) {
            case DOCKER_SINGLE -> DockerRuntimeKind.CONTAINER;
            case SWARM_STACK -> DockerRuntimeKind.SERVICE;
            case AUTO -> throw unsupportedComputeAdapter();
        };
    }

    private static IllegalStateException unsupportedComputeAdapter() {
        return new IllegalStateException("ComputeAdapter must expose a concrete adapter type");
    }
}
