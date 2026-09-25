package io.pockethive.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ManagerSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerEngineTest {
    private final DockerClient client = mock(DockerClient.class);
    private final DockerEngine engine = new DockerEngine(client);

    @Test
    void rejectsOrchestratorSwarmModeOnNonManagerNode() {
        swarmInfo(new SwarmInfo().withLocalNodeState(LocalNodeState.ACTIVE).withControlAvailable(false));
        assertThatThrownBy(() -> engine.orchestratorAdapter(ComputeAdapterType.SWARM_STACK))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not a Swarm manager");
        verify(client, never()).createServiceCmd(any());
    }

    @Test
    void controllerRequiresResolvedModeWithoutProbingDaemon() {
        assertThatThrownBy(() -> engine.controllerAdapter(ComputeAdapterType.AUTO))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTO");
        verifyNoInteractions(client);
    }

    @Test
    void autoOnActiveManagerCreatesAService() {
        swarmInfo(new SwarmInfo().withLocalNodeState(LocalNodeState.ACTIVE).withControlAvailable(true));
        var create = mock(CreateServiceCmd.class);
        var response = mock(CreateServiceResponse.class);
        when(response.getId()).thenReturn("service-id");
        when(client.createServiceCmd(any())).thenReturn(create);
        when(create.exec()).thenReturn(response);
        assertThat(engine.orchestratorAdapter(ComputeAdapterType.AUTO).startManager(manager())).isEqualTo("service-id");
        verify(client, never()).createContainerCmd(anyString());
    }

    @Test
    void autoWithoutSwarmCreatesAndRemovesContainer() {
        swarmInfo(null);
        var create = mock(CreateContainerCmd.class, RETURNS_SELF);
        var response = new CreateContainerResponse();
        response.setId("container-id");
        when(client.createContainerCmd("controller:test")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(client.startContainerCmd("container-id")).thenReturn(mock(StartContainerCmd.class));
        when(client.stopContainerCmd("container-id")).thenReturn(mock(StopContainerCmd.class));
        when(client.removeContainerCmd("container-id")).thenReturn(mock(RemoveContainerCmd.class));
        var compute = engine.orchestratorAdapter(ComputeAdapterType.AUTO);
        assertThat(compute.startManager(manager())).isEqualTo("container-id");
        compute.stopManager("container-id");
        verify(client).stopContainerCmd("container-id");
        verify(client).removeContainerCmd("container-id");
        verify(client, never()).createServiceCmd(any());
    }

    @Test
    void explicitModesDoNotAddDaemonProbes() {
        engine.orchestratorAdapter(ComputeAdapterType.DOCKER_SINGLE);
        engine.controllerAdapter(ComputeAdapterType.SWARM_STACK);
        engine.controllerAdapter(null);
        verifyNoInteractions(client);
    }

    @Test
    void releasesOwnedConnection() throws Exception {
        engine.close();
        verify(client).close();
    }

    private void swarmInfo(SwarmInfo swarm) {
        var cmd = mock(InfoCmd.class);
        var info = mock(Info.class);
        when(client.infoCmd()).thenReturn(cmd);
        when(cmd.exec()).thenReturn(info);
        when(info.getSwarm()).thenReturn(swarm);
    }

    private static ManagerSpec manager() {
        return new ManagerSpec("manager", "controller:test", Map.of(
            "POCKETHIVE_CONTROL_PLANE_SWARM_ID", "Hive-I",
            "POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "manager",
            "POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", "swarm-controller",
            "POCKETHIVE_JOURNAL_RUN_ID", "run"), List.of());
    }
}
