package io.pockethive.swarmcontroller.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerConfiguration {
  private final SwarmControllerProperties properties;

  public DockerConfiguration(SwarmControllerProperties properties) {
    this.properties = properties;
  }

  @Bean
  public DefaultDockerClientConfig dockerClientConfig() {
    DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
    SwarmControllerProperties.Docker docker = properties.getDocker();
    if (docker.hasHost()) {
      builder.withDockerHost(docker.host());
    } else {
      builder.withDockerHost("unix://" + docker.socketPath());
    }
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

}
