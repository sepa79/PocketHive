package io.pockethive.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.HostConfig;

import java.util.Map;

public class DockerContainerClient {
    private final DockerClient dockerClient;

    public DockerContainerClient(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public String createAndStartContainer(String image, Map<String, String> env) {
        String[] envArray = toEnvArray(env);
        CreateContainerResponse response = dockerClient.createContainerCmd(image)
            .withHostConfig(HostConfig.newHostConfig().withNetworkMode(resolveControlNetwork()))
            .withEnv(envArray)
            .exec();
        StartContainerCmd start = dockerClient.startContainerCmd(response.getId());
        start.exec();
        return response.getId();
    }

    public String createAndStartContainer(String image) {
        return createAndStartContainer(image, Map.of());
    }

    public String createContainer(String image, Map<String, String> env) {
        String[] envArray = toEnvArray(env);
        CreateContainerResponse response = dockerClient.createContainerCmd(image)
            .withHostConfig(HostConfig.newHostConfig().withNetworkMode(resolveControlNetwork()))
            .withEnv(envArray)
            .exec();
        return response.getId();
    }

    public String createContainer(String image) {
        return createContainer(image, Map.of());
    }

    public void startContainer(String containerId) {
        dockerClient.startContainerCmd(containerId).exec();
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

    private String[] toEnvArray(Map<String, String> env) {
        return env.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .toArray(String[]::new);
    }
}
