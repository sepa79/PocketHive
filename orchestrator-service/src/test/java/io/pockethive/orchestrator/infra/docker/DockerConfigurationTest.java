package io.pockethive.orchestrator.infra.docker;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DockerConfigurationTest {
    @Test
    void createsDockerClient() {
        DockerConfiguration config = new DockerConfiguration();
        DockerClient client = config.dockerClient();
        assertNotNull(client);
    }
}
