package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactStore;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwarmStatusDiscoveryTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeCleanupPorts.ComputeRuntimeInventoryPort inventory = mock(RuntimeCleanupPorts.ComputeRuntimeInventoryPort.class);

    private RuntimeCleanupPorts.ComputeRuntimeResource resource(String id, String runId) {
        var labels = PocketHiveDockerLabels.managerLabels("inst1", "controller:test", Map.of(
            "POCKETHIVE_CONTROL_PLANE_SWARM_ID", "sw1", "POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", "swarm-controller",
            "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "inst1", "POCKETHIVE_JOURNAL_RUN_ID", runId,
            "POCKETHIVE_TEMPLATE_ID", "tpl-1"), ComputeAdapterType.DOCKER_SINGLE);
        return new RuntimeCleanupPorts.ComputeRuntimeResource(id, "container", "inst1", "controller:test", "running", labels);
    }

    @Test
    void discoversExactRuntimeWithVerifiedLocalArtifactAndPublishedReference() {
        var artifacts = new FilesystemSwarmStartupArtifactStore(mapper, RuntimeFilesystemLayout.of(root.toString(), "/runtime"));
        var saved = artifacts.save("sw1", SwarmStartupArtifact.v1(new SwarmPlan("sw1", List.of()), Map.of()));
        when(inventory.list()).thenReturn(List.of(resource("other", "old-run"), resource("actual-id", "run-1")));
        var service = new SwarmStatusDiscovery(inventory, artifacts);
        var swarm = service.discover("sw1", "inst1", "run-1", "tpl-1", NetworkMode.DIRECT,
            mapper.createObjectNode().put("startupArtifactSha256", saved.sha256()));
        assertThat(swarm.getContainerId()).isEqualTo("actual-id");
        assertThat(swarm.getRunId()).isEqualTo("run-1");
        assertThat(swarm.getWorkloadIntent()).isEqualTo(io.pockethive.swarm.model.lifecycle.WorkloadIntent.UNKNOWN);
        swarm.requestWorkload(io.pockethive.swarm.model.lifecycle.WorkloadIntent.RUNNING);
        assertThat(swarm.getWorkloadIntent()).isEqualTo(io.pockethive.swarm.model.lifecycle.WorkloadIntent.RUNNING);
        swarm.requestWorkload(io.pockethive.swarm.model.lifecycle.WorkloadIntent.STOPPED);
        assertThat(swarm.getWorkloadIntent()).isEqualTo(io.pockethive.swarm.model.lifecycle.WorkloadIntent.STOPPED);
        assertThat(swarm.startupArtifact()).isEqualTo(saved);
        assertThat(swarm.controllerImage()).isEqualTo("controller:test");
        assertThat(swarm.templateId()).isEqualTo("tpl-1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2})
    void rejectsAbsentOrAmbiguousRuntimeWithoutGuessing(int count) {
        var artifacts = new FilesystemSwarmStartupArtifactStore(mapper, RuntimeFilesystemLayout.of(root.toString(), "/runtime"));
        when(inventory.list()).thenReturn(count == 0 ? List.of() : List.of(resource("one", "run-1"), resource("two", "run-1")));
        assertThatThrownBy(() -> new SwarmStatusDiscovery(inventory, artifacts).discover(
            "sw1", "inst1", "run-1", "tpl-1", NetworkMode.DIRECT, mapper.createObjectNode()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one matching runtime");
    }

    @Test
    void rejectsMissingStartupArtifact() {
        var artifacts = new FilesystemSwarmStartupArtifactStore(mapper, RuntimeFilesystemLayout.of(root.toString(), "/runtime"));
        when(inventory.list()).thenReturn(List.of(resource("one", "run-1")));
        assertThatThrownBy(() -> new SwarmStatusDiscovery(inventory, artifacts).discover(
            "sw1", "inst1", "run-1", "tpl-1", NetworkMode.DIRECT,
            mapper.createObjectNode().put("startupArtifactSha256", "a".repeat(64))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to load startup artifact");
    }
}
