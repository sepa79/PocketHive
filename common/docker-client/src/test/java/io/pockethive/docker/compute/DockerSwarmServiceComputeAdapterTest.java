package io.pockethive.docker.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        managerEnv("swarm-1", "auth-rollout-swarm-f46d24db-marshal-bee-stingy-puff-2625"),
        null));

    ArgumentCaptor<ServiceSpec> specCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
    verify(docker).createServiceCmd(specCaptor.capture());
    ServiceSpec spec = specCaptor.getValue();

    assertThat(spec.getName()).isEqualTo("auth-rollout-swarm-f46d24db-marshal-bee-stingy-puff-2625");
    assertThat(spec.getLabels()).containsEntry(
        "ph.logicalName", "auth-rollout-swarm-f46d24db-marshal-bee-stingy-puff-2625");
    assertThat(spec.getLabels()).containsEntry("pockethive.managed", "true");
    assertThat(spec.getLabels()).containsEntry("pockethive.resourceKind", "manager");
    assertThat(spec.getLabels()).containsEntry("pockethive.owner", "orchestrator");
    assertThat(spec.getLabels()).containsEntry("pockethive.swarmId", "swarm-1");
    assertThat(spec.getLabels()).containsEntry("pockethive.runId", "run-1");
    assertThat(spec.getLabels()).containsEntry("pockethive.role", "swarm-controller");
    assertThat(spec.getLabels()).containsEntry(
        "pockethive.instance", "auth-rollout-swarm-f46d24db-marshal-bee-stingy-puff-2625");
    assertThat(spec.getLabels()).containsEntry("pockethive.computeAdapter", "SWARM_STACK");
    assertThat(spec.getLabels()).containsEntry("pockethive.version", "test");
    assertThat(spec.getLabels()).containsKey("pockethive.createdAt");
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
        workerEnv(
            "swarm-1",
            "postprocessor-worker",
            "postprocessor",
            Map.of("POCKETHIVE_OUTPUTS_REDIS_TARGETLISTTEMPLATE", "webauth.RED.{{ payloadAsJson.Customer }}")),
        null)));

    ArgumentCaptor<ServiceSpec> specCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
    verify(docker).createServiceCmd(specCaptor.capture());
    ServiceSpec spec = specCaptor.getValue();

    assertThat(spec.getTaskTemplate().getContainerSpec().getEnv())
        .contains("POCKETHIVE_OUTPUTS_REDIS_TARGETLISTTEMPLATE=webauth.RED.{{ \"{{\" }} payloadAsJson.Customer {{ \"}}\" }}");
  }

  @Test
  void labelsWorkerServicesWithPocketHiveOwnership() {
    DockerClient docker = mock(DockerClient.class);
    CreateServiceCmd create = mock(CreateServiceCmd.class);
    CreateServiceResponse response = mock(CreateServiceResponse.class);
    when(response.getId()).thenReturn("service-id");
    when(docker.createServiceCmd(any())).thenReturn(create);
    when(create.exec()).thenReturn(response);

    DockerSwarmServiceComputeAdapter adapter =
        new DockerSwarmServiceComputeAdapter(docker, () -> "pockethive_default");

    adapter.applyWorkers("swarm-1", List.of(new WorkerSpec(
        "processor-worker",
        "processor",
        "processor:test",
        workerEnv("swarm-1", "processor-worker", "processor", Map.of()),
        null)));

    ArgumentCaptor<ServiceSpec> specCaptor = ArgumentCaptor.forClass(ServiceSpec.class);
    verify(docker).createServiceCmd(specCaptor.capture());
    ServiceSpec spec = specCaptor.getValue();

    assertThat(spec.getLabels()).containsEntry("pockethive.managed", "true");
    assertThat(spec.getLabels()).containsEntry("pockethive.resourceKind", "worker");
    assertThat(spec.getLabels()).containsEntry("pockethive.owner", "swarm-controller");
    assertThat(spec.getLabels()).containsEntry("pockethive.swarmId", "swarm-1");
    assertThat(spec.getLabels()).containsEntry("pockethive.runId", "run-1");
    assertThat(spec.getLabels()).containsEntry("pockethive.role", "processor");
    assertThat(spec.getLabels()).containsEntry("pockethive.instance", "processor-worker");
    assertThat(spec.getLabels()).containsEntry("pockethive.computeAdapter", "SWARM_STACK");
    assertThat(spec.getLabels()).containsEntry("pockethive.image", "processor:test");
    assertThat(spec.getLabels()).containsEntry("pockethive.version", "test");
  }

  @Test
  void managerRemovalFailureIsPropagated() {
    DockerClient docker = mock(DockerClient.class);
    when(docker.removeServiceCmd("service-id")).thenThrow(new IllegalStateException("docker unavailable"));

    DockerSwarmServiceComputeAdapter adapter =
        new DockerSwarmServiceComputeAdapter(docker, () -> "pockethive_default");

    assertThatThrownBy(() -> adapter.stopManager("service-id"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("docker unavailable");
  }

  private static void assertNetwork(NetworkAttachmentConfig attachment) {
    assertThat(attachment.getTarget()).isEqualTo("pockethive_default");
  }

  private static Map<String, String> managerEnv(String swarmId, String instanceId) {
    return Map.of(
        "POCKETHIVE_CONTROL_PLANE_SWARM_ID", swarmId,
        "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", instanceId,
        "POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", "swarm-controller",
        "POCKETHIVE_JOURNAL_RUN_ID", "run-1",
        "POCKETHIVE_VERSION", "0.15.27",
        "POCKETHIVE_TEMPLATE_ID", "template-1");
  }

  private static Map<String, String> workerEnv(
      String swarmId,
      String instanceId,
      String role,
      Map<String, String> extra) {
    java.util.LinkedHashMap<String, String> env = new java.util.LinkedHashMap<>();
    env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", swarmId);
    env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", instanceId);
    env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ROLE", role);
    env.put("POCKETHIVE_JOURNAL_RUN_ID", "run-1");
    env.put("POCKETHIVE_VERSION", "0.15.27");
    env.put("POCKETHIVE_TEMPLATE_ID", "template-1");
    env.putAll(extra);
    return Map.copyOf(env);
  }
}
