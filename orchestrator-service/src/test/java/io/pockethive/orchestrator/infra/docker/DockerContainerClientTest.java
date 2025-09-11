package io.pockethive.orchestrator.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.command.CreateContainerResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DockerContainerClientTest {

    @Test
    void setsNetworkModeFromEnv() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class);
        StartContainerCmd start = mock(StartContainerCmd.class);
        CreateContainerResponse resp = new CreateContainerResponse();
        resp.setId("cid");
        when(docker.createContainerCmd("img")).thenReturn(create);
        when(create.withHostConfig(any())).thenReturn(create);
        when(create.withEnv(any(String[].class))).thenReturn(create);
        when(create.exec()).thenReturn(resp);
        when(docker.startContainerCmd("cid")).thenReturn(start);

        DockerContainerClient client = new DockerContainerClient(docker);
        client.createAndStartContainer("img", Map.of());

        ArgumentCaptor<HostConfig> hostCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(hostCaptor.capture());
        assertThat(hostCaptor.getValue().getNetworkMode()).isEqualTo(System.getenv("CONTROL_NETWORK"));
    }
}
