package io.pockethive.swarmcontroller.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;

import java.util.List;
import java.util.Map;

public class DockerContainerClient {
  private final DockerClient dockerClient;

  public DockerContainerClient(DockerClient dockerClient) {
    this.dockerClient = dockerClient;
  }

  public String createAndStartContainer(String image, Map<String, String> env) {
    CreateContainerResponse response = dockerClient.createContainerCmd(image)
        .withHostConfig(HostConfig.newHostConfig())
        .withEnv(toEnvList(env))
        .exec();
    dockerClient.startContainerCmd(response.getId()).exec();
    return response.getId();
  }

  public String createAndStartContainer(String image) {
    return createAndStartContainer(image, Map.of());
  }

  public String createContainer(String image, Map<String, String> env) {
    CreateContainerResponse response = dockerClient.createContainerCmd(image)
        .withHostConfig(HostConfig.newHostConfig())
        .withEnv(toEnvList(env))
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

  private List<String> toEnvList(Map<String, String> env) {
    return env.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .toList();
  }
}
