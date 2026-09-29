package io.pockethive.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerRuntimeClientTest {
    private final DockerClient docker = mock(DockerClient.class);
    private final DockerRuntimeClient runtime = new DockerRuntimeClient(docker, new ObjectMapper());

    @Test
    void listsStoppedContainersWithExistingProjectionAndImmutableLabels() {
        var command = mock(ListContainersCmd.class, RETURNS_SELF);
        var container = mock(Container.class);
        when(docker.listContainersCmd()).thenReturn(command);
        when(command.exec()).thenReturn(List.of(container));
        when(container.getId()).thenReturn("id");
        when(container.getNames()).thenReturn(new String[]{"/worker", "/alias"});
        when(container.getImage()).thenReturn("image");
        when(container.getState()).thenReturn("exited");
        when(container.getCreated()).thenReturn(1L);
        when(container.getLabels()).thenReturn(Map.of("owner", "test"));
        var result = runtime.list(DockerRuntimeKind.CONTAINER);
        assertThat(result).containsExactly(new DockerRuntimeResource("id", DockerRuntimeKind.CONTAINER,
            "worker", "image", "exited", "1970-01-01T00:00:01Z", null, null, Map.of("owner", "test")));
        verify(command).withShowAll(true);
        assertThatThrownBy(() -> result.getFirst().labels().put("x", "y"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listsServicesIncludingMissingSpecWithoutInventingState() {
        var command = mock(ListServicesCmd.class);
        var service = mock(Service.class);
        when(docker.listServicesCmd()).thenReturn(command);
        when(command.exec()).thenReturn(List.of(service));
        when(service.getId()).thenReturn("svc");
        when(service.getCreatedAt()).thenReturn(new Date(1000));
        assertThat(runtime.list(DockerRuntimeKind.SERVICE)).containsExactly(
            new DockerRuntimeResource("svc", DockerRuntimeKind.SERVICE, null, null, "service",
                "1970-01-01T00:00:01Z", null, null, Map.of()));
    }

    @Test
    void removesContainerWithForceAndPropagatesAbsence() {
        var command = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd("id")).thenReturn(command);
        var failure = new NotFoundException("gone");
        doThrow(failure).when(command).exec();
        assertThatThrownBy(() -> runtime.removeContainer("id")).isSameAs(failure);
        verify(command).withForce(true);
        verify(docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void removesServiceWithoutInterpretingResult() {
        var command = mock(RemoveServiceCmd.class);
        when(docker.removeServiceCmd("id")).thenReturn(command);
        runtime.removeService("id");
        verify(command).exec();
    }

    @Test
    void inspectReturnsNormalizedServiceProjection() {
        var command = mock(InspectServiceCmd.class);
        var service = new Service().withId("svc");
        when(docker.inspectServiceCmd("svc")).thenReturn(command);
        when(command.exec()).thenReturn(service);
        assertThat(runtime.inspect(DockerRuntimeKind.SERVICE, "svc").state().status()).isEqualTo("service");
    }

    @Test
    void containerLogsPreserveOptionsAndUtf8Payload() {
        var command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(docker.logContainerCmd("id")).thenReturn(command);
        doAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onNext(null);
            callback.onNext(new Frame(StreamType.STDOUT, "zażółć".getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        }).when(command).exec(any());
        assertThat(runtime.logs(DockerRuntimeKind.CONTAINER, "id", 20, 123)).isEqualTo("zażółć");
        verify(command).withStdOut(true);
        verify(command).withStdErr(true);
        verify(command).withTimestamps(true);
        verify(command).withTail(20);
        verify(command).withFollowStream(false);
        verify(command).withSince(123);
    }

    @Test
    void serviceLogsPreserveOptionsAndOmitUnsetSince() {
        var command = mock(LogSwarmObjectCmd.class, RETURNS_SELF);
        when(docker.logServiceCmd("id")).thenReturn(command);
        doAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDERR, "error".getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        }).when(command).exec(any());
        assertThat(runtime.logs(DockerRuntimeKind.SERVICE, "id", 10, null)).isEqualTo("error");
        verify(command).withStdout(true);
        verify(command).withStderr(true);
        verify(command).withTimestamps(true);
        verify(command).withTail(10);
        verify(command).withFollow(false);
        verify(command, never()).withSince(anyInt());
    }
}
