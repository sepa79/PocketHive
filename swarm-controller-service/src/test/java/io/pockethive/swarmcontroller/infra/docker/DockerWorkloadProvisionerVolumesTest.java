package io.pockethive.swarmcontroller.infra.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import io.pockethive.docker.DockerContainerClient;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DockerWorkloadProvisionerVolumesTest {

  @Test
  void passesVolumeBindingsToDockerClient() {
    RecordingDockerContainerClient client = new RecordingDockerContainerClient();
    DockerWorkloadProvisioner provisioner = new DockerWorkloadProvisioner(client);

    List<String> volumes = List.of(
        "/host/a:/container/a:ro",
        "named-vol:/container/cache");

    String id = provisioner.createAndStart(
        "test-image:latest",
        "test-container",
        Map.of("FOO", "BAR"),
        volumes);

    assertThat(id).isEqualTo("container-123");
    assertThat(client.lastImage).isEqualTo("test-image:latest");
    assertThat(client.lastName).isEqualTo("test-container");
    assertThat(client.lastEnv).containsEntry("FOO", "BAR");

    HostConfig hostConfig = client.lastHostConfig;
    assertThat(hostConfig).isNotNull();
    Bind[] binds = hostConfig.getBinds();
    assertThat(binds).isNotNull();
    assertThat(binds).hasSize(2);
    assertThat(binds[0].getPath()).isEqualTo("/host/a");
    assertThat(binds[0].getVolume().getPath()).isEqualTo("/container/a");
    assertThat(binds[1].getPath()).isEqualTo("named-vol");
    assertThat(binds[1].getVolume().getPath()).isEqualTo("/container/cache");
  }

  private static final class RecordingDockerContainerClient extends DockerContainerClient {

    String lastImage;
    String lastName;
    Map<String, String> lastEnv;
    HostConfig lastHostConfig;

    RecordingDockerContainerClient() {
      super(null);
    }

    @Override
    public String createAndStartContainer(String image,
                                          Map<String, String> env,
                                          String containerName,
                                          UnaryOperator<HostConfig> hostConfigCustomizer) {
      this.lastImage = image;
      this.lastName = containerName;
      this.lastEnv = env;
      HostConfig base = HostConfig.newHostConfig();
      if (hostConfigCustomizer != null) {
        lastHostConfig = hostConfigCustomizer.apply(base);
      } else {
        lastHostConfig = base;
      }
      return "container-123";
    }
  }
}
