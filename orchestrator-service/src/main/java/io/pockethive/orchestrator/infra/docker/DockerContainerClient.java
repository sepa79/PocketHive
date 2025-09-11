package io.pockethive.orchestrator.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.HostConfig;

public class DockerContainerClient {
    private final DockerClient dockerClient;

    public DockerContainerClient(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public String createAndStartContainer(String image, java.util.Map<String, String> env) {
        String[] envArray = env.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .toArray(String[]::new);
        CreateContainerResponse response = dockerClient.createContainerCmd(image)
            .withHostConfig(HostConfig.newHostConfig()
                .withNetworkMode(resolveControlNetwork()))
            .withEnv(envArray)
            .exec();
        dockerClient.startContainerCmd(response.getId()).exec();
        return response.getId();
    }

    public void stopAndRemoveContainer(String containerId) {
        dockerClient.stopContainerCmd(containerId).exec();
        dockerClient.removeContainerCmd(containerId).exec();
    }

    public String resolveControlNetwork() {
        String net = System.getenv("CONTROL_NETWORK");
        if (net == null || net.isBlank()) {
            try {
                String self = System.getenv("HOSTNAME");
                if (self != null) {
                    InspectContainerResponse inspect = dockerClient.inspectContainerCmd(self).exec();
                    net = inspect.getNetworkSettings().getNetworks().keySet().stream()
                        .filter(n -> !"bridge".equals(n))
                        .findFirst().orElse(null);
                }
            } catch (Exception ignored) {
            }
        }
        return net;
    }
}
