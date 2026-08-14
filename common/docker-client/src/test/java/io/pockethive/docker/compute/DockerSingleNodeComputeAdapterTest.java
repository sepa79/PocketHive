package io.pockethive.docker.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import io.pockethive.docker.DockerContainerClient;
import io.pockethive.manager.runtime.WorkerSpec;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DockerSingleNodeComputeAdapterTest {

  @Test
  void labelsWorkerContainersWithPocketHiveOwnership() {
    DockerContainerClient docker = mock(DockerContainerClient.class);
    when(docker.createAndStartContainer(
        eq("processor:test"),
        any(),
        eq("processor-worker"),
        any(),
        any())).thenReturn("container-id");

    DockerSingleNodeComputeAdapter adapter = new DockerSingleNodeComputeAdapter(docker);

    adapter.applyWorkers("swarm-1", List.of(new WorkerSpec(
        "processor-worker",
        "processor",
        "processor:test",
        workerEnv(),
        null)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> labelsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(docker).createAndStartContainer(
        eq("processor:test"),
        eq(workerEnv()),
        eq("processor-worker"),
        any(UnaryOperator.class),
        labelsCaptor.capture());

    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.managed", "true");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.resourceKind", "worker");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.owner", "swarm-controller");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.swarmId", "swarm-1");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.runId", "run-1");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.role", "processor");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.instance", "processor-worker");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.computeAdapter", "DOCKER_SINGLE");
    assertThat(labelsCaptor.getValue()).containsEntry("pockethive.version", "test");
  }

  @Test
  void workerRemovalFailureIsPropagatedAndRemainsRetryable() {
    DockerContainerClient docker = mock(DockerContainerClient.class);
    when(docker.createAndStartContainer(
        eq("processor:test"), any(), eq("processor-worker"), any(), any()))
        .thenReturn("container-id");
    doThrow(new IllegalStateException("docker unavailable"))
        .when(docker).stopAndRemoveContainer("container-id");
    DockerSingleNodeComputeAdapter adapter = new DockerSingleNodeComputeAdapter(docker);
    adapter.applyWorkers("swarm-1", List.of(new WorkerSpec(
        "processor-worker", "processor", "processor:test", workerEnv(), null)));

    assertThatThrownBy(() -> adapter.removeWorkers("swarm-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("docker unavailable");
    assertThatThrownBy(() -> adapter.removeWorkers("swarm-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("docker unavailable");
  }

  private static Map<String, String> workerEnv() {
    return Map.of(
        "POCKETHIVE_CONTROL_PLANE_SWARM_ID", "swarm-1",
        "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "processor-worker",
        "POCKETHIVE_CONTROL_PLANE_WORKER_ROLE", "processor",
        "POCKETHIVE_JOURNAL_RUN_ID", "run-1",
        "POCKETHIVE_VERSION", "0.15.27",
        "POCKETHIVE_TEMPLATE_ID", "template-1");
  }
}
