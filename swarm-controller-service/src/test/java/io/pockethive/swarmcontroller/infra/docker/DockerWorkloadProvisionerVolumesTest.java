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
        workerEnv(),
        volumes);

    assertThat(id).isEqualTo("container-123");
    assertThat(client.lastImage).isEqualTo("test-image:latest");
    assertThat(client.lastName).isEqualTo("test-container");
    assertThat(client.lastEnv).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_ID", "swarm-1");
    assertThat(client.lastLabels)
        .containsEntry("pockethive.managed", "true")
        .containsEntry("pockethive.resourceKind", "worker")
        .containsEntry("pockethive.owner", "swarm-controller")
        .containsEntry("pockethive.swarmId", "swarm-1")
        .containsEntry("pockethive.runId", "run-1")
        .containsEntry("pockethive.role", "generator")
        .containsEntry("pockethive.instance", "test-container")
        .containsEntry("pockethive.image", "test-image:latest")
        .containsEntry("pockethive.version", "latest");

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

  @Test
  void labelsContainersWithoutVolumeBindings() {
    RecordingDockerContainerClient client = new RecordingDockerContainerClient();
    DockerWorkloadProvisioner provisioner = new DockerWorkloadProvisioner(client);

    String id = provisioner.createAndStart("generator:test", "test-container", workerEnv());

    assertThat(id).isEqualTo("container-123");
    assertThat(client.lastLabels)
        .containsEntry("pockethive.managed", "true")
        .containsEntry("pockethive.resourceKind", "worker")
        .containsEntry("pockethive.swarmId", "swarm-1")
        .containsEntry("pockethive.role", "generator")
        .containsEntry("pockethive.version", "test");
  }

  private static Map<String, String> workerEnv() {
    return Map.of(
        "POCKETHIVE_CONTROL_PLANE_SWARM_ID", "swarm-1",
        "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "test-container",
        "POCKETHIVE_CONTROL_PLANE_WORKER_ROLE", "generator",
        "POCKETHIVE_JOURNAL_RUN_ID", "run-1",
        "POCKETHIVE_TEMPLATE_ID", "local-rest-defaults");
  }

  private static final class RecordingDockerContainerClient extends DockerContainerClient {

    String lastImage;
    String lastName;
    Map<String, String> lastEnv;
    HostConfig lastHostConfig;
    Map<String, String> lastLabels;

    RecordingDockerContainerClient() {
      super(null);
    }

    @Override
    public String createAndStartContainer(String image,
                                          Map<String, String> env,
                                          String containerName,
                                          UnaryOperator<HostConfig> hostConfigCustomizer,
                                          Map<String, String> labels) {
      this.lastImage = image;
      this.lastName = containerName;
      this.lastEnv = env;
      this.lastLabels = labels;
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
