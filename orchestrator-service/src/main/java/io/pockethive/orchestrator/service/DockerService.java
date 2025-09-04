package io.pockethive.orchestrator.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DockerService {
    private static final Logger logger = LoggerFactory.getLogger(DockerService.class);
    private final DockerClient dockerClient;

    public DockerService() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .build();
        this.dockerClient = DockerClientBuilder.getInstance(config)
                .withDockerHttpClient(httpClient)
                .build();
    }

    public String createContainer(String imageName, String containerName, Map<String, String> environment) {
        try {
            CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
                    .withName(containerName)
                    .withEnv(environment.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toArray(String[]::new))
                    .exec();
            
            logger.info("Created container {} with ID {}", containerName, container.getId());
            return container.getId();
        } catch (Exception e) {
            logger.error("Failed to create container {}: {}", containerName, e.getMessage());
            throw new RuntimeException("Failed to create container", e);
        }
    }

    public void startContainer(String containerId) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
            logger.info("Started container {}", containerId);
        } catch (Exception e) {
            logger.error("Failed to start container {}: {}", containerId, e.getMessage());
            throw new RuntimeException("Failed to start container", e);
        }
    }

    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).exec();
            logger.info("Stopped container {}", containerId);
        } catch (Exception e) {
            logger.error("Failed to stop container {}: {}", containerId, e.getMessage());
            throw new RuntimeException("Failed to stop container", e);
        }
    }

    public void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).exec();
            logger.info("Removed container {}", containerId);
        } catch (Exception e) {
            logger.error("Failed to remove container {}: {}", containerId, e.getMessage());
            throw new RuntimeException("Failed to remove container", e);
        }
    }

    public List<Container> listContainers() {
        return dockerClient.listContainersCmd().withShowAll(true).exec();
    }
}