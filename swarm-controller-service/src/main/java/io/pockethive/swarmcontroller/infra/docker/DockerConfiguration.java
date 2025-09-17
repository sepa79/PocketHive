package io.pockethive.swarmcontroller.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.pockethive.docker.DockerContainerClient;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerConfiguration {
  @Bean
  public DefaultDockerClientConfig dockerClientConfig() {
    DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
    resolveDockerHostOverride().ifPresent(builder::withDockerHost);
    return builder.build();
  }

  @Bean
  public DockerClient dockerClient(DefaultDockerClientConfig config) {
    DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }

  @Bean
  public DockerContainerClient dockerContainerClient(DockerClient dockerClient) {
    return new DockerContainerClient(dockerClient);
  }

  private Optional<String> resolveDockerHostOverride() {
    return Optional.ofNullable(System.getenv("DOCKER_HOST"))
        .filter(host -> !host.isBlank())
        .or(() -> Optional.ofNullable(System.getProperty("DOCKER_HOST")))
        .filter(host -> !host.isBlank())
        .or(() -> Optional.ofNullable(System.getProperty("docker.host")))
        .filter(host -> !host.isBlank());
  }
}
