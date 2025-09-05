package io.pockethive.orchestrator.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerConfiguration {
    @Bean
    public DockerClient dockerClient() {
        return DockerClientBuilder.getInstance().build();
    }

    @Bean
    public DockerContainerClient dockerContainerClient(DockerClient dockerClient) {
        return new DockerContainerClient(dockerClient);
    }
}
