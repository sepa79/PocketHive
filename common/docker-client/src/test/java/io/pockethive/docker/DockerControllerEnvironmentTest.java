package io.pockethive.docker;

import io.pockethive.manager.runtime.ComputeAdapterType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DockerControllerEnvironmentTest {
    @Test
    void exportsOneSocketToControllerPropertiesHostAndMount() {
        var env = DockerControllerEnvironment.encode("/custom/docker.sock", ComputeAdapterType.SWARM_STACK);
        assertThat(env).hasSize(4)
            .containsEntry("DOCKER_HOST", "unix:///custom/docker.sock")
            .containsEntry("DOCKER_SOCKET_PATH", "/custom/docker.sock")
            .containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", "/custom/docker.sock")
            .containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_COMPUTE_ADAPTER", "SWARM_STACK");
        assertThat(DockerControllerEnvironment.socketMount("/custom/docker.sock"))
            .isEqualTo("/custom/docker.sock:/custom/docker.sock");
    }
}
