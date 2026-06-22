package io.pockethive.orchestrator.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Service;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeRemovalPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeDebugPorts.ComputeRuntimeDebugPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DockerRuntimeAdapter implements ComputeRuntimeInventoryPort, ComputeRuntimeRemovalPort, ComputeRuntimeDebugPort {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper;
    private final ComputeAdapter computeAdapter;

    public DockerRuntimeAdapter(DockerClient dockerClient, ObjectMapper objectMapper, ComputeAdapter computeAdapter) {
        this.dockerClient = Objects.requireNonNull(dockerClient, "dockerClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
    }

    @Override
    public List<ComputeRuntimeResource> list() {
        return switch (adapterType()) {
            case DOCKER_SINGLE -> listContainers();
            case SWARM_STACK -> listServices();
            case AUTO -> throw unsupportedComputeAdapter();
        };
    }

    @Override
    public void removeContainer(String runtimeId) {
        dockerClient.removeContainerCmd(runtimeId).withForce(true).exec();
    }

    @Override
    public void removeService(String runtimeId) {
        dockerClient.removeServiceCmd(runtimeId).exec();
    }

    @Override
    public Map<String, Object> inspect(String runtimeId) {
        Object response = switch (adapterType()) {
            case DOCKER_SINGLE -> dockerClient.inspectContainerCmd(runtimeId).exec();
            case SWARM_STACK -> dockerClient.inspectServiceCmd(runtimeId).exec();
            case AUTO -> throw unsupportedComputeAdapter();
        };
        return objectMapper.convertValue(response, MAP_TYPE);
    }

    @Override
    public String logs(String runtimeId, int tailLines, Integer sinceEpochSeconds) {
        StringBuilder logs = new StringBuilder();
        try {
            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame item) {
                    if (item != null && item.getPayload() != null) {
                        logs.append(new String(item.getPayload(), StandardCharsets.UTF_8));
                    }
                }
            };
            switch (adapterType()) {
                case DOCKER_SINGLE -> {
                    var command = dockerClient.logContainerCmd(runtimeId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withTimestamps(true)
                        .withTail(tailLines)
                        .withFollowStream(false);
                    if (sinceEpochSeconds != null) {
                        command.withSince(sinceEpochSeconds);
                    }
                    command.exec(callback).awaitCompletion();
                }
                case SWARM_STACK -> {
                    var command = dockerClient.logServiceCmd(runtimeId)
                        .withStdout(true)
                        .withStderr(true)
                        .withTimestamps(true)
                        .withTail(tailLines)
                        .withFollow(false);
                    if (sinceEpochSeconds != null) {
                        command.withSince(sinceEpochSeconds);
                    }
                    command.exec(callback).awaitCompletion();
                }
                case AUTO -> throw unsupportedComputeAdapter();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while reading runtime logs", ex);
        }
        return logs.toString();
    }

    private List<ComputeRuntimeResource> listContainers() {
        List<ComputeRuntimeResource> resources = new ArrayList<>();
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        for (Container container : containers) {
            Map<String, String> labels = container.getLabels() == null ? Map.of() : container.getLabels();
            resources.add(new ComputeRuntimeResource(
                container.getId(),
                RuntimeCleanupPorts.RUNTIME_TYPE_CONTAINER,
                firstName(container.getNames()),
                container.getImage(),
                container.getState(),
                epochSeconds(container.getCreated()),
                null,
                null,
                labels));
        }
        return List.copyOf(resources);
    }

    private List<ComputeRuntimeResource> listServices() {
        List<ComputeRuntimeResource> resources = new ArrayList<>();
        List<Service> services = dockerClient.listServicesCmd().exec();
        for (Service service : services) {
            var spec = service.getSpec();
            Map<String, String> labels = spec != null && spec.getLabels() != null ? spec.getLabels() : Map.of();
            String image = null;
            if (spec != null
                && spec.getTaskTemplate() != null
                && spec.getTaskTemplate().getContainerSpec() != null) {
                image = spec.getTaskTemplate().getContainerSpec().getImage();
            }
            resources.add(new ComputeRuntimeResource(
                service.getId(),
                RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE,
                spec == null ? null : spec.getName(),
                image,
                RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE,
                instant(service.getCreatedAt()),
                null,
                null,
                labels));
        }
        return List.copyOf(resources);
    }

    private static String firstName(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        String name = names[0];
        if (name == null) {
            return null;
        }
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static String epochSeconds(Long seconds) {
        return seconds == null ? null : Instant.ofEpochSecond(seconds).toString();
    }

    private static String instant(Date value) {
        return value == null ? null : value.toInstant().toString();
    }

    private ComputeAdapterType adapterType() {
        ComputeAdapterType adapterType = computeAdapter.type();
        if (adapterType == null || adapterType == ComputeAdapterType.AUTO) {
            throw unsupportedComputeAdapter();
        }
        return adapterType;
    }

    private static IllegalStateException unsupportedComputeAdapter() {
        return new IllegalStateException("ComputeAdapter must expose a concrete adapter type");
    }
}
