package io.pockethive.docker;

import io.pockethive.manager.runtime.RuntimeInspection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: execute Docker runtime inventory, diagnostics and explicit removal.
 * Must not: decide cleanup eligibility, lifecycle outcomes or compute selection.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public final class DockerRuntimeClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper;

    DockerRuntimeClient(DockerClient dockerClient, ObjectMapper objectMapper) {
        this.dockerClient = Objects.requireNonNull(dockerClient, "dockerClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<DockerRuntimeResource> list(DockerRuntimeKind kind) {
        return switch (kind) {
            case CONTAINER -> listContainers();
            case SERVICE -> listServices();
        };
    }

    public void removeContainer(String runtimeId) {
        dockerClient.removeContainerCmd(runtimeId).withForce(true).exec();
    }

    public void removeService(String runtimeId) {
        dockerClient.removeServiceCmd(runtimeId).exec();
    }

    public RuntimeInspection inspect(DockerRuntimeKind kind, String runtimeId) {
        Object response = switch (kind) {
            case CONTAINER -> dockerClient.inspectContainerCmd(runtimeId).exec();
            case SERVICE -> dockerClient.inspectServiceCmd(runtimeId).exec();
        };
        return DockerInspectMapper.map(kind, objectMapper.convertValue(response, MAP_TYPE));
    }

    public String logs(DockerRuntimeKind kind, String runtimeId, int tailLines, Integer sinceEpochSeconds) {
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
            switch (kind) {
                case CONTAINER -> {
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
                case SERVICE -> {
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
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while reading runtime logs", ex);
        }
        return logs.toString();
    }

    private List<DockerRuntimeResource> listContainers() {
        List<DockerRuntimeResource> resources = new ArrayList<>();
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        for (Container container : containers) {
            Map<String, String> labels = container.getLabels() == null ? Map.of() : container.getLabels();
            resources.add(new DockerRuntimeResource(
                container.getId(),
                DockerRuntimeKind.CONTAINER,
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

    private List<DockerRuntimeResource> listServices() {
        List<DockerRuntimeResource> resources = new ArrayList<>();
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
            resources.add(new DockerRuntimeResource(
                service.getId(),
                DockerRuntimeKind.SERVICE,
                spec == null ? null : spec.getName(),
                image,
                DockerRuntimeKind.SERVICE.stateLabel(),
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

}
