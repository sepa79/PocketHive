package io.pockethive.docker.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateServiceCmd;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.model.NetworkAttachmentConfig;
import com.github.dockerjava.api.model.ServiceSpec;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.manager.runtime.WorkerSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DockerSwarmServiceComputeAdapterTest {

  @Test
  void shortensLongDockerServiceNamesDeterministically() {
    String logicalName = "auth-bundle-runner-allowed-eab599a0-marshal-bee-nectar-puff-9a3f";

    String serviceName = DockerSwarmServiceComputeAdapter.dockerServiceName(logicalName);

    assertThat(serviceName).hasSizeLessThanOrEqualTo(
        DockerSwarmServiceComputeAdapter.DOCKER_SERVICE_NAME_MAX_LENGTH);
    assertThat(serviceName).startsWith("auth-bundle-runner-allowed-eab599a0-marshal-bee-nect");
    assertThat(serviceName).matches("[a-z0-9-]+-[a-f0-9]{10}");
    assertThat(serviceName).isEqualTo(DockerSwarmServiceComputeAdapter.dockerServiceName(logicalName));
  }

  @Test
  void attachesControlNetworkToServiceAndTaskSpec() {
    DockerClient docker = mock(DockerClient.class);
    CreateServiceCmd create = mock(CreateServiceCmd.class);
    CreateServiceResponse response = mock(CreateServiceResponse.class);
    when(response.getId()).thenReturn("service-id");
    when(docker.createServiceCmd(any())).thenReturn(create);
    when(create.exec()).thenReturn(response);

    DockerSwarmServiceComputeAdapter adapter =
        new DockerSwarmServiceComputeAdapter(docker, () -> "pockethive_default");

    adapter.startManager(new ManagerSpec(
        "auth-rollout-swarm-f46d24db-marshal-bee-stingy-puff-2625",
        "swarm-controller:test",
        Map.of("POCKETHIVE_CONTROL_PLANE_SWARM_ID", "swarm-1"),
        null));

    ArgumentCaptor<ServiceSpec> specCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
    verify(docker).createServiceCmd(specCaptor.capture());
    ServiceSpec spec = specCaptor.getValue();

    assertThat(spec.getName()).isEqualTo("auth-rollout-swarm-f46d24db-marshal-bee-stingy-puff-2625");
    assertThat(spec.getLabels()).containsEntry(
        "ph.logicalName", "auth-rollout-swarm-f46d24db-marshal-bee-stingy-puff-2625");
    assertNetwork(spec.getNetworks().get(0));
    assertNetwork(spec.getTaskTemplate().getNetworks().get(0));
  }

  @Test
  void escapesApplicationTemplateDelimitersInServiceEnvironment() {
    DockerClient docker = mock(DockerClient.class);
    CreateServiceCmd create = mock(CreateServiceCmd.class);
    CreateServiceResponse response = mock(CreateServiceResponse.class);
    when(response.getId()).thenReturn("service-id");
    when(docker.createServiceCmd(any())).thenReturn(create);
    when(create.exec()).thenReturn(response);

    DockerSwarmServiceComputeAdapter adapter =
        new DockerSwarmServiceComputeAdapter(docker, () -> "pockethive_default");

    adapter.applyWorkers("swarm-1", List.of(new WorkerSpec(
        "postprocessor-worker",
        "postprocessor",
        "postprocessor:test",
        Map.of(
            "POCKETHIVE_CONTROL_PLANE_SWARM_ID", "swarm-1",
            "POCKETHIVE_OUTPUTS_REDIS_TARGETLISTTEMPLATE", "webauth.RED.{{ payloadAsJson.Customer }}"),
        null)));

    ArgumentCaptor<ServiceSpec> specCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
    verify(docker).createServiceCmd(specCaptor.capture());
    ServiceSpec spec = specCaptor.getValue();

    assertThat(spec.getTaskTemplate().getContainerSpec().getEnv())
        .contains("POCKETHIVE_OUTPUTS_REDIS_TARGETLISTTEMPLATE=webauth.RED.{{ \"{{\" }} payloadAsJson.Customer {{ \"}}\" }}");
  }

  private static void assertNetwork(NetworkAttachmentConfig attachment) {
    assertThat(attachment.getTarget()).isEqualTo("pockethive_default");
  }
}
