package io.pockethive.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DockerContainerClientTest {

    @Test
    void usesResolvedNetworkMode() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class);
        StartContainerCmd start = mock(StartContainerCmd.class);
        CreateContainerResponse resp = new CreateContainerResponse();
        resp.setId("cid");
        when(docker.createContainerCmd("img")).thenReturn(create);
        when(create.withHostConfig(any())).thenReturn(create);
        when(create.withEnv(any(String[].class))).thenReturn(create);
        when(create.withName(anyString())).thenReturn(create);
        when(create.exec()).thenReturn(resp);
        when(docker.startContainerCmd("cid")).thenReturn(start);

        DockerContainerClient client = new DockerContainerClient(docker) {
            @Override
            public String resolveControlNetwork() {
                return "net1";
            }
        };
        client.createAndStartContainer("img", Map.of(), "bee-one");

        ArgumentCaptor<HostConfig> hostCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(hostCaptor.capture());
        assertThat(hostCaptor.getValue().getNetworkMode()).isEqualTo("net1");
        verify(create).withName("bee-one");
    }

    @Test
    void wrapsMissingDockerSocketWithHelpfulMessage() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class);
        when(docker.createContainerCmd("img")).thenReturn(create);
        when(create.withHostConfig(any())).thenReturn(create);
        when(create.withEnv(any(String[].class))).thenReturn(create);
        when(create.withName(anyString())).thenReturn(create);
        when(create.exec()).thenThrow(new RuntimeException(new java.io.IOException("No such file or directory")));

        DockerContainerClient client = new DockerContainerClient(docker);

        assertThatThrownBy(() -> client.createContainer("img", Map.of(), "bee-one"))
            .isInstanceOf(DockerDaemonUnavailableException.class)
            .hasMessageContaining("Docker daemon is unavailable");
    }

    @Test
    void wrapsConnectionRefusedOnStart() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(docker.startContainerCmd("cid")).thenReturn(start);
        doThrow(new RuntimeException(new ConnectException("Connection refused"))).when(start).exec();

        DockerContainerClient client = new DockerContainerClient(docker);

        assertThatThrownBy(() -> client.startContainer("cid"))
            .isInstanceOf(DockerDaemonUnavailableException.class)
            .hasMessageContaining("Docker daemon is unavailable");
    }

    @Test
    void appliesHostConfigCustomizer() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class);
        StartContainerCmd start = mock(StartContainerCmd.class);
        CreateContainerResponse resp = new CreateContainerResponse();
        resp.setId("cid");
        when(docker.createContainerCmd("img")).thenReturn(create);
        when(create.withHostConfig(any())).thenReturn(create);
        when(create.withEnv(any(String[].class))).thenReturn(create);
        when(create.withName(anyString())).thenReturn(create);
        when(create.exec()).thenReturn(resp);
        when(docker.startContainerCmd("cid")).thenReturn(start);

        DockerContainerClient client = new DockerContainerClient(docker);

        client.createAndStartContainer("img", Map.of(), "bee-one",
            hostConfig -> hostConfig.withBinds(Bind.parse("/host:/container")));

        ArgumentCaptor<HostConfig> hostCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(hostCaptor.capture());
        assertThat(hostCaptor.getValue().getBinds()).containsExactly(Bind.parse("/host:/container"));
    }
}
