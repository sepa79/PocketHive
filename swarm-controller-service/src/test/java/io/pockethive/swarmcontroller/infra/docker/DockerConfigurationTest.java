package io.pockethive.swarmcontroller.infra.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DockerConfigurationTest {

  private final DockerConfiguration configuration = new DockerConfiguration();

  @Test
  void dockerClientConfigHonorsDockerHostEnvironmentVariable() {
    Assumptions.assumeTrue(isDockerHostEnvUnset(), "DOCKER_HOST env must be unset for this test");
    String previousUpper = System.getProperty("DOCKER_HOST");
    String previousLower = System.getProperty("docker.host");
    System.setProperty("DOCKER_HOST", "unix:///custom/docker.sock");
    System.clearProperty("docker.host");
    try {
      DefaultDockerClientConfig config = configuration.dockerClientConfig();
      assertThat(config.getDockerHost().toString()).isEqualTo("unix:///custom/docker.sock");
    } finally {
      if (previousUpper == null) {
        System.clearProperty("DOCKER_HOST");
      } else {
        System.setProperty("DOCKER_HOST", previousUpper);
      }
      if (previousLower == null) {
        System.clearProperty("docker.host");
      } else {
        System.setProperty("docker.host", previousLower);
      }
    }
  }

  private boolean isDockerHostEnvUnset() {
    String envValue = System.getenv("DOCKER_HOST");
    return envValue == null || envValue.isBlank();
  }
}
