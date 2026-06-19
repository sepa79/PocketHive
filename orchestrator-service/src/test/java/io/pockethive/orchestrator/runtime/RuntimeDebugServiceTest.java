package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTargetRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugPorts.ComputeRuntimeDebugPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeDebugServiceTest {

    @Test
    void listPartitionsManagersWorkersAndBlocksIncompleteLabels() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("manager-1", "container", "manager", "swarm-controller", "controller-1"),
            runtime("worker-1", "container", "worker", "processor", "processor-1"),
            new ComputeRuntimeResource(
                "partial-1",
                "container",
                "partial-1",
                "processor:test",
                "exited",
                Map.of(
                    PocketHiveDockerLabels.MANAGED, PocketHiveDockerLabels.MANAGED_VALUE,
                    PocketHiveDockerLabels.SWARM_ID, "sw1"))));

        var response = service(runtime).list(new ResourceListRequest("DOCKER_SINGLE", "sw1", "run-1", true));

        assertThat(response.workers()).extracting("runtimeId").containsExactly("worker-1");
        assertThat(response.managers()).extracting("runtimeId").containsExactly("manager-1");
        assertThat(response.blocked()).extracting("runtimeId").containsExactly("partial-1");
        assertThat(response.workers().get(0).reportedVersion()).isEqualTo("0.15.27");
    }

    @Test
    void listFiltersRunIdAndCanExcludeManagers() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("manager-1", "container", "manager", "swarm-controller", "controller-1"),
            runtime("worker-1", "container", "worker", "processor", "processor-1"),
            runtime("worker-old-run", "container", "worker", "processor", "processor-old", "sw1", "run-0")));

        var response = service(runtime).list(new ResourceListRequest(" DOCKER_SINGLE ", "sw1", "run-1", false));

        assertThat(response.computeAdapter()).isEqualTo("DOCKER_SINGLE");
        assertThat(response.workers()).extracting("runtimeId").containsExactly("worker-1");
        assertThat(response.managers()).isEmpty();
        assertThat(response.counts().workers()).isEqualTo(1);
        assertThat(runtime.listCalls).containsExactly("DOCKER_SINGLE");
    }

    @Test
    void managerLogsAreLabelGatedBoundedAndRedacted() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("manager-1", "container", "manager", "swarm-controller", "controller-1")));
        runtime.logs = "Authorization: Bearer clear-token\npassword=open\nok";

        var response = service(runtime).logs(new RuntimeLogsRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            null,
            "controller-1",
            null,
            "manager",
            20,
            "2026-06-18T12:00:00Z"));

        assertThat(response.target().resourceKind()).isEqualTo("manager");
        assertThat(response.tailLines()).isEqualTo(20);
        assertThat(response.logs()).contains("Authorization: Bearer [REDACTED]");
        assertThat(response.logs()).contains("password=[REDACTED]");
        assertThat(runtime.logCalls).containsExactly("DOCKER_SINGLE:manager-1:20:1781784000");
    }

    @Test
    void logRequestsValidateBoundsTargetAndSinceBeforeReadingLogs() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("worker-1", "container", "worker", "processor", "processor-1")));

        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "DOCKER_SINGLE", "sw1", "run-1", "worker-1", null, null, "worker", 0, null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("tailLines");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "DOCKER_SINGLE", "sw1", "run-1", "worker-1", null, null, "worker", 2001, null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("tailLines");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "DOCKER_SINGLE", "sw1", "run-1", "worker-1", null, null, "worker", 10, "not-a-time")))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("since");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "DOCKER_SINGLE", "sw1", "run-1", null, null, null, "worker", 10, null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("runtimeId, instance, or role");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "DOCKER_SINGLE", "sw1", "run-1", "worker-1", null, null, "sidecar", 10, null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("resourceKind");
        assertThat(runtime.logCalls).isEmpty();
    }

    @Test
    void targetSelectionRejectsAmbiguousWorkerRole() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("worker-1", "container", "worker", "processor", "processor-1"),
            runtime("worker-2", "container", "worker", "processor", "processor-2")));

        assertThatThrownBy(() -> service(runtime).version(new RuntimeTargetRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            null,
            null,
            "processor",
            "worker")))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("ambiguous");
    }

    @Test
    void targetSelectionRejectsIncompleteLabeledTarget() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PocketHiveDockerLabels.MANAGED, PocketHiveDockerLabels.MANAGED_VALUE);
        labels.put(PocketHiveDockerLabels.SWARM_ID, "sw1");
        labels.put(PocketHiveDockerLabels.RUN_ID, "run-1");
        labels.put(PocketHiveDockerLabels.RESOURCE_KIND, "worker");
        FakeRuntime runtime = new FakeRuntime(List.of(new ComputeRuntimeResource(
            "worker-partial",
            "container",
            "worker-partial",
            "processor:0.15.27",
            "exited",
            labels)));

        assertThatThrownBy(() -> service(runtime).version(new RuntimeTargetRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            "worker-partial",
            null,
            null,
            "worker")))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("missing required labels");
    }

    @Test
    void versionUsesDeclaredRuntimeLabelBeforeImageTag() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("manager-1", "container", "manager", "swarm-controller", "controller-1")));

        var response = service(runtime).version(new RuntimeTargetRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            "manager-1",
            null,
            null,
            "manager"));

        assertThat(response.reportedVersion()).isEqualTo("0.15.27");
        assertThat(response.reportedVersionSource()).isEqualTo(PocketHiveDockerLabels.VERSION);
    }

    @Test
    void versionFallsBackToImageTagWithoutMistakingRegistryPortForTag() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("worker-tagged", "container", "worker", "processor", "processor-1", "sw1", "run-1",
                "localhost:5000/pockethive/processor:0.15.28", false),
            runtime("worker-untagged", "container", "worker", "processor", "processor-2", "sw1", "run-1",
                "localhost:5000/pockethive/processor", false)));

        var tagged = service(runtime).version(new RuntimeTargetRequest(
            "DOCKER_SINGLE", "sw1", "run-1", "worker-tagged", null, null, "worker"));
        var untagged = service(runtime).version(new RuntimeTargetRequest(
            "DOCKER_SINGLE", "sw1", "run-1", "worker-untagged", null, null, "worker"));

        assertThat(tagged.reportedVersion()).isEqualTo("0.15.28");
        assertThat(tagged.reportedVersionSource()).isEqualTo("imageTag");
        assertThat(untagged.reportedVersion()).isNull();
        assertThat(untagged.imageTag()).isNull();
    }

    @Test
    void runtimeDebugRequestsValidateAdapterAndBody() {
        FakeRuntime runtime = new FakeRuntime(List.of());

        assertThatThrownBy(() -> service(runtime).list(null))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("request body is required");
        assertThatThrownBy(() -> service(runtime).list(new ResourceListRequest("AUTO", "sw1", "run-1", true)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("computeAdapter must be concrete");
        assertThatThrownBy(() -> service(runtime).list(new ResourceListRequest("BOGUS", "sw1", "run-1", true)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("unsupported computeAdapter");
        assertThatThrownBy(() -> service(runtime).version(null))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("request body is required");
    }

    @Test
    void inspectSanitizesBindMountSources() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("worker-1", "container", "worker", "processor", "processor-1")));
        runtime.inspect = Map.of(
            "Created", "2026-01-01T00:00:00Z",
            "RestartCount", 2,
            "State", Map.of(
                "Status", "exited",
                "Running", false,
                "ExitCode", 137,
                "StartedAt", "2026-01-01T00:01:00Z",
                "FinishedAt", "2026-01-01T00:02:00Z",
                "Health", Map.of("Status", "unhealthy")),
            "HostConfig", Map.of("RestartPolicy", Map.of("Name", "on-failure")),
            "Mounts", List.of(
                Map.of("Type", "bind", "Source", "/host/secret", "Destination", "/app", "RW", false),
                Map.of("Type", "volume", "Name", "ph-data", "Source", "ph-data", "Destination", "/data", "RW", true)),
            "NetworkSettings", Map.of("Networks", Map.of("bridge", Map.of(), "pockethive", Map.of())));

        var response = service(runtime).inspect(new RuntimeTargetRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            "worker-1",
            null,
            null,
            "worker"));

        assertThat(response.restartCount()).isEqualTo(2);
        assertThat(response.state()).containsEntry("exitCode", 137);
        assertThat(response.mounts().get(0)).containsEntry("source", "[REDACTED]");
        assertThat(response.mounts().get(1)).containsEntry("name", "ph-data");
        assertThat(response.networks()).containsExactly("bridge", "pockethive");
    }

    @Test
    void serviceInspectSanitizesBindMountSourcesAndReportsNetworks() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("service-1", "service", "worker", "processor", "processor-1")));
        runtime.inspect = Map.of(
            "CreatedAt", "2026-01-01T00:00:00Z",
            "Spec", Map.of(
                "TaskTemplate", Map.of(
                    "ContainerSpec", Map.of(
                        "Mounts", List.of(
                            Map.of("Type", "bind", "Source", "/host/secret", "Target", "/app", "ReadOnly", true),
                            Map.of("Type", "volume", "Source", "ph-data", "Target", "/data", "ReadOnly", false))),
                    "RestartPolicy", Map.of("Condition", "on-failure"),
                    "Networks", List.of(Map.of("Target", "net-worker")))));

        var response = service(runtime).inspect(new RuntimeTargetRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            "service-1",
            null,
            null,
            "worker"));

        assertThat(response.state()).containsEntry("status", "service");
        assertThat(response.restartPolicy()).isEqualTo("on-failure");
        assertThat(response.mounts().get(0)).containsEntry("source", "[REDACTED]");
        assertThat(response.mounts().get(1)).containsEntry("name", "ph-data");
        assertThat(response.networks()).containsExactly("net-worker");
    }

    private static RuntimeDebugService service(FakeRuntime runtime) {
        return new RuntimeDebugService(runtime, runtime);
    }

    private static ComputeRuntimeResource runtime(String runtimeId,
                                                  String runtimeType,
                                                  String resourceKind,
                                                  String role,
                                                  String instance) {
        return runtime(runtimeId, runtimeType, resourceKind, role, instance, "sw1", "run-1");
    }

    private static ComputeRuntimeResource runtime(String runtimeId,
                                                  String runtimeType,
                                                  String resourceKind,
                                                  String role,
                                                  String instance,
                                                  String swarmId,
                                                  String runId) {
        return runtime(runtimeId, runtimeType, resourceKind, role, instance, swarmId, runId, role + ":0.15.27", true);
    }

    private static ComputeRuntimeResource runtime(String runtimeId,
                                                  String runtimeType,
                                                  String resourceKind,
                                                  String role,
                                                  String instance,
                                                  String swarmId,
                                                  String runId,
                                                  String image,
                                                  boolean includeDeclaredVersion) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PocketHiveDockerLabels.MANAGED, PocketHiveDockerLabels.MANAGED_VALUE);
        labels.put(PocketHiveDockerLabels.SWARM_ID, swarmId);
        labels.put(PocketHiveDockerLabels.RUN_ID, runId);
        labels.put(PocketHiveDockerLabels.RESOURCE_KIND, resourceKind);
        labels.put(PocketHiveDockerLabels.ROLE, role);
        labels.put(PocketHiveDockerLabels.INSTANCE, instance);
        labels.put(PocketHiveDockerLabels.LOGICAL_NAME, instance);
        labels.put(PocketHiveDockerLabels.IMAGE, image);
        if (includeDeclaredVersion) {
            labels.put(PocketHiveDockerLabels.VERSION, "0.15.27");
        }
        return new ComputeRuntimeResource(
            runtimeId,
            runtimeType,
            runtimeId,
            image,
            "container".equals(runtimeType) ? "running" : "service",
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:01:00Z",
            null,
            labels);
    }

    private static final class FakeRuntime implements ComputeRuntimeInventoryPort, ComputeRuntimeDebugPort {
        private final List<ComputeRuntimeResource> resources;
        private final List<String> listCalls = new ArrayList<>();
        private final List<String> logCalls = new ArrayList<>();
        private String logs = "";
        private Map<String, Object> inspect = Map.of();

        private FakeRuntime(List<ComputeRuntimeResource> resources) {
            this.resources = resources;
        }

        @Override
        public List<ComputeRuntimeResource> list(String computeAdapter) {
            listCalls.add(computeAdapter);
            return resources;
        }

        @Override
        public Map<String, Object> inspect(String computeAdapter, String runtimeId) {
            return inspect;
        }

        @Override
        public String logs(String computeAdapter, String runtimeId, int tailLines, Integer sinceEpochSeconds) {
            logCalls.add(computeAdapter + ":" + runtimeId + ":" + tailLines + ":" + sinceEpochSeconds);
            return logs;
        }
    }
}
