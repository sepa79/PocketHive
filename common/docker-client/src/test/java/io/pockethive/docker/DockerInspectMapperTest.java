package io.pockethive.docker;

import io.pockethive.manager.runtime.RuntimeInspectionState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DockerInspectMapperTest {
    @Test
    void containerProjectionPreservesStateMountValuesAndNetworkOrder() {
        Map<String, Object> raw = Map.of(
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
        var result = DockerInspectMapper.map(DockerRuntimeKind.CONTAINER, raw);
        assertThat(result.state()).isEqualTo(new RuntimeInspectionState("exited", false, 137, null,
            "unhealthy", "2026-01-01T00:01:00Z", "2026-01-01T00:02:00Z"));
        assertThat(result.createdAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(result.restartCount()).isEqualTo(2);
        assertThat(result.restartPolicy()).isEqualTo("on-failure");
        assertThat(result.networks()).containsExactly("bridge", "pockethive");
        var bind = result.mounts().getFirst();
        assertThat(bind.source()).isEqualTo("/host/secret");
        assertThat(bind.destination()).isEqualTo("/app");
        assertThat(bind.writable()).isEqualTo(true);
        assertThat(bind.reportsPropagation()).isTrue();
        assertThat(result.mounts().get(1).writable()).isEqualTo(false);
        assertThat(result.mounts().get(1).name()).isEqualTo("ph-data");
    }

    @Test
    void serviceProjectionPreservesDiagnosticStateAndMountFieldAvailability() {
        Map<String, Object> raw = Map.of(
            "CreatedAt", "2026-01-01T00:00:00Z",
            "Spec", Map.of(
                "TaskTemplate", Map.of(
                    "ContainerSpec", Map.of(
                        "Mounts", List.of(
                            Map.of("Type", "bind", "Source", "/host/secret", "Target", "/app", "ReadOnly", true),
                            Map.of("Type", "volume", "Source", "ph-data", "Target", "/data", "ReadOnly", false))),
                    "RestartPolicy", Map.of("Condition", "on-failure"),
                    "Networks", List.of(Map.of("Target", "net-worker")))));
        var result = DockerInspectMapper.map(DockerRuntimeKind.SERVICE, raw);
        assertThat(result.state()).isEqualTo(new RuntimeInspectionState("service", true, null, null, null, null, null));
        assertThat(result.createdAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(result.restartCount()).isNull();
        assertThat(result.restartPolicy()).isEqualTo("on-failure");
        assertThat(result.networks()).containsExactly("net-worker");
        var bind = result.mounts().getFirst();
        assertThat(bind.source()).isEqualTo("/host/secret");
        assertThat(bind.destination()).isEqualTo("/app");
        assertThat(bind.mode()).isEqualTo("ro");
        assertThat(bind.writable()).isEqualTo(false);
        assertThat(bind.reportsPropagation()).isFalse();
        assertThat(result.mounts().get(1).name()).isEqualTo("ph-data");
        assertThat(result.mounts().get(1).mode()).isEqualTo("rw");
    }

    @Test
    void preservesExistingAliasPrecedenceIncludingExplicitNull() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("ExitCode", null);
        state.put("exitCode", 42);
        state.put("running", false);
        state.put("error", "  ");
        var result = DockerInspectMapper.map(DockerRuntimeKind.CONTAINER,
            Map.of("state", state, "restartCount", " 2 ", "created", " timestamp "));
        assertThat(result.state().exitCode()).isNull();
        assertThat(result.state().error()).isNull();
        assertThat(result.state().running()).isEqualTo(false);
        assertThat(result.restartCount()).isEqualTo(2);
        assertThat(result.createdAt()).isEqualTo("timestamp");
    }

    @Test
    void keepsExistingServiceNetworkPrecedenceAndSortedIdentifiers() {
        var result = DockerInspectMapper.map(DockerRuntimeKind.SERVICE,
            Map.of("spec", Map.of("taskTemplate", Map.of("networks", List.of()),
                "networks", List.of(Map.of("networkID", "z"), Map.of("Name", "a")))));
        assertThat(result.networks()).containsExactly("a", "z");
        var preferred = DockerInspectMapper.map(DockerRuntimeKind.SERVICE,
            Map.of("Spec", Map.of("TaskTemplate", Map.of("Networks", List.of(Map.of("Target", "preferred"))),
                "Networks", List.of(Map.of("Target", "ignored")))));
        assertThat(preferred.networks()).containsExactly("preferred");
    }

    @Test
    void missingFieldsRemainUnknownWithoutInferringState() {
        var result = DockerInspectMapper.map(DockerRuntimeKind.CONTAINER, Map.of());
        assertThat(result.state()).isEqualTo(new RuntimeInspectionState(null, null, null, null, null, null, null));
        assertThat(result.restartCount()).isNull();
        assertThat(result.mounts()).isEmpty();
        assertThat(result.networks()).isEmpty();
    }
}
