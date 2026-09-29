package io.pockethive.docker;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DockerConnectionsTest {
    @Test
    void explicitHostTakesPrecedenceOverSocketPath() {
        assertThat(DockerConnections.controller("unix:///explicit.sock", "/unused.sock").getDockerHost().toString())
            .isEqualTo("unix:///explicit.sock");
    }

    @Test
    void absentHostUsesConfiguredSocketPath() {
        assertThat(DockerConnections.controller(null, "/custom.sock").getDockerHost().toString())
            .isEqualTo("unix:///custom.sock");
        assertThat(DockerConnections.controller(" ", "/custom.sock").getDockerHost().toString())
            .isEqualTo("unix:///custom.sock");
    }
}
