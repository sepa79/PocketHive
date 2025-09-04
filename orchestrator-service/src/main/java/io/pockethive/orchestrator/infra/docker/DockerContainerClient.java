package io.pockethive.orchestrator.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;

public class DockerContainerClient {
    private final DockerClient dockerClient;

    public DockerContainerClient(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public String createAndStartContainer(String image) {
        CreateContainerResponse response = dockerClient.createContainerCmd(image)
                .withHostConfig(HostConfig.newHostConfig())
                .exec();
        dockerClient.startContainerCmd(response.getId()).exec();
        return response.getId();
    }

    public void stopAndRemoveContainer(String containerId) {
        dockerClient.stopContainerCmd(containerId).exec();
        dockerClient.removeContainerCmd(containerId).exec();
    }
}
