package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ManagerSpec;
import io.pockethive.manager.runtime.WorkerSpec;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTargetRequest;
import io.pockethive.manager.ports.ComputeRuntimeDebugPort;
import io.pockethive.manager.runtime.RuntimeInspection;
import io.pockethive.manager.runtime.RuntimeInspectionState;
import io.pockethive.manager.runtime.RuntimeMountInspection;
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

        var response = service(runtime).list(new ResourceListRequest("sw1", "run-1", true));

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

        var response = service(runtime).list(new ResourceListRequest("sw1", "run-1", false));

        assertThat(response.computeAdapter()).isEqualTo("DOCKER_SINGLE");
        assertThat(response.workers()).extracting("runtimeId").containsExactly("worker-1");
        assertThat(response.managers()).isEmpty();
        assertThat(response.counts().workers()).isEqualTo(1);
        assertThat(runtime.listCalls).isEqualTo(1);
    }

    @Test
    void managerLogsAreLabelGatedBoundedAndRedacted() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("manager-1", "container", "manager", "swarm-controller", "controller-1")));
        runtime.logs = "Authorization: Bearer clear-token\npassword=open\nok";

        var response = service(runtime).logs(new RuntimeLogsRequest(
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
        assertThat(runtime.logCalls).containsExactly("manager-1:20:1781784000");
    }

    @Test
    void logRequestsValidateBoundsTargetAndSinceBeforeReadingLogs() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("worker-1", "container", "worker", "processor", "processor-1")));

        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "sw1", "run-1", "worker-1", null, null, "worker", 0, null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("tailLines");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "sw1", "run-1", "worker-1", null, null, "worker", 2001, null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("tailLines");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "sw1", "run-1", "worker-1", null, null, "worker", 10, "not-a-time")))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("since");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "sw1", "run-1", null, null, null, "worker", 10, null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("runtimeId, instance, or role");
        assertThatThrownBy(() -> service(runtime).logs(new RuntimeLogsRequest(
            "sw1", "run-1", "worker-1", null, null, "sidecar", 10, null)))
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
            "sw1", "run-1", "worker-tagged", null, null, "worker"));
        var untagged = service(runtime).version(new RuntimeTargetRequest(
            "sw1", "run-1", "worker-untagged", null, null, "worker"));

        assertThat(tagged.reportedVersion()).isEqualTo("0.15.28");
        assertThat(tagged.reportedVersionSource()).isEqualTo("imageTag");
        assertThat(untagged.reportedVersion()).isNull();
        assertThat(untagged.imageTag()).isNull();
    }

    @Test
    void runtimeDebugRequestsValidateBody() {
        FakeRuntime runtime = new FakeRuntime(List.of());

        assertThatThrownBy(() -> service(runtime).list(null))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("request body is required");
        assertThatThrownBy(() -> service(runtime).version(null))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessageContaining("request body is required");
    }

    @Test
    void inspectSanitizesBindMountSources() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("worker-1", "container", "worker", "processor", "processor-1")));
        runtime.inspect = new RuntimeInspection(new RuntimeInspectionState("exited", false, 137, null,
            "unhealthy", "2026-01-01T00:01:00Z", "2026-01-01T00:02:00Z"),
            "2026-01-01T00:00:00Z", 2, "on-failure", List.of(
                new RuntimeMountInspection("bind", null, "/host/secret", "/app", null, true, null, true),
                new RuntimeMountInspection("volume", "ph-data", "ph-data", "/data", null, false, null, true)),
            List.of("bridge", "pockethive"));

        var response = service(runtime).inspect(new RuntimeTargetRequest(
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
        assertThat(response.mounts().get(0)).containsEntry("propagation", null).containsEntry("rw", true);
        assertThat(response.mounts().get(1)).containsEntry("source", "ph-data").containsEntry("rw", false);
        assertThat(response.state()).containsEntry("error", null).containsEntry("running", false);
        assertThat(response.source()).containsEntry("available", true);
    }

    @Test
    void serviceInspectSanitizesBindMountSourcesAndReportsNetworks() {
        FakeRuntime runtime = new FakeRuntime(List.of(
            runtime("service-1", "service", "worker", "processor", "processor-1")));
        runtime.inspect = new RuntimeInspection(new RuntimeInspectionState("service", true, null, null, null, null, null),
            "2026-01-01T00:00:00Z", null, "on-failure", List.of(
                new RuntimeMountInspection("bind", null, "/host/secret", "/app", "ro", false, null, false),
                new RuntimeMountInspection("volume", "ph-data", "ph-data", "/data", "rw", true, null, false)),
            List.of("net-worker"));

        var response = service(runtime).inspect(new RuntimeTargetRequest(
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
        assertThat(response.mounts().get(0)).doesNotContainKey("propagation").containsEntry("mode", "ro");
        assertThat(response.mounts().get(1)).containsEntry("source", "ph-data").containsEntry("rw", true);
        assertThat(response.state()).containsEntry("exitCode", null).containsEntry("running", true);
    }

    private static RuntimeDebugService service(FakeRuntime runtime) {
        return new RuntimeDebugService(runtime, runtime, new FakeComputeAdapter());
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
        private int listCalls;
        private final List<String> logCalls = new ArrayList<>();
        private String logs = "";
        private RuntimeInspection inspect;

        private FakeRuntime(List<ComputeRuntimeResource> resources) {
            this.resources = resources;
        }

        @Override
        public List<ComputeRuntimeResource> list() {
            listCalls++;
            return resources;
        }

        @Override
        public RuntimeInspection inspect(String runtimeId) {
            return inspect;
        }

        @Override
        public String logs(String runtimeId, int tailLines, Integer sinceEpochSeconds) {
            logCalls.add(runtimeId + ":" + tailLines + ":" + sinceEpochSeconds);
            return logs;
        }
    }

    private static final class FakeComputeAdapter implements ComputeAdapter {
        @Override
        public ComputeAdapterType type() {
            return ComputeAdapterType.DOCKER_SINGLE;
        }

        @Override
        public String startManager(ManagerSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopManager(String managerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyWorkers(String topologyId, List<WorkerSpec> workers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeWorkers(String topologyId) {
            throw new UnsupportedOperationException();
        }
    }
}
