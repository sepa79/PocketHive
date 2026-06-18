package io.pockethive.orchestrator.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Service;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeRemovalPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DockerRuntimeAdapter implements ComputeRuntimeInventoryPort, ComputeRuntimeRemovalPort {
    private static final String DOCKER_SINGLE = "DOCKER_SINGLE";
    private static final String SWARM_STACK = "SWARM_STACK";

    private final DockerClient dockerClient;

    public DockerRuntimeAdapter(DockerClient dockerClient) {
        this.dockerClient = Objects.requireNonNull(dockerClient, "dockerClient");
    }

    @Override
    public List<ComputeRuntimeResource> list(String computeAdapter) {
        return switch (computeAdapter) {
            case DOCKER_SINGLE -> listContainers();
            case SWARM_STACK -> listServices();
            default -> throw new IllegalArgumentException("unsupported computeAdapter: " + computeAdapter);
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

    private List<ComputeRuntimeResource> listContainers() {
        List<ComputeRuntimeResource> resources = new ArrayList<>();
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        for (Container container : containers) {
            Map<String, String> labels = container.getLabels() == null ? Map.of() : container.getLabels();
            resources.add(new ComputeRuntimeResource(
                container.getId(),
                "container",
                firstName(container.getNames()),
                container.getImage(),
                container.getState(),
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
                "service",
                spec == null ? null : spec.getName(),
                image,
                "service",
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
}
