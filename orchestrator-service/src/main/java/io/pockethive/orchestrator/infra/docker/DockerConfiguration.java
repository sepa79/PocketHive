package io.pockethive.orchestrator.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerConfiguration {
    @Bean
    public DockerClient dockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        return DockerClientBuilder.getInstance(config).build();
    }

    @Bean
    public DockerContainerClient dockerContainerClient(DockerClient dockerClient) {
        return new DockerContainerClient(dockerClient);
    }
}
