package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSwarmStartupArtifactStoreTest {

    @TempDir
    Path runtimeRoot;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void writesContentAddressedArtifactAndReturnsContainerReference() throws Exception {
        FilesystemSwarmStartupArtifactStore store =
            new FilesystemSwarmStartupArtifactStore(mapper, runtimeRoot.toString());

        var stored = store.save("swarm-a", artifact("swarm-a"));

        assertThat(stored.sha256()).matches("[0-9a-f]{64}");
        assertThat(stored.path()).isEqualTo(
            "/app/scenarios-runtime/swarm-a/runtime-artifacts/startup-" + stored.sha256() + ".json");
        Path file = runtimeRoot.resolve("swarm-a/runtime-artifacts/startup-" + stored.sha256() + ".json");
        assertThat(FilesystemSwarmStartupArtifactStore.sha256(Files.readAllBytes(file)))
            .isEqualTo(stored.sha256());
        assertThat(mapper.readValue(file.toFile(), SwarmStartupArtifact.class)).isEqualTo(artifact("swarm-a"));
    }

    @Test
    void rejectsSwarmIdThatEscapesRuntimeRoot() {
        FilesystemSwarmStartupArtifactStore store =
            new FilesystemSwarmStartupArtifactStore(mapper, runtimeRoot.toString());

        assertThatThrownBy(() -> store.save("../outside", artifact("swarm-a")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("single path segment");
    }

    private static SwarmStartupArtifact artifact(String swarmId) {
        return SwarmStartupArtifact.v1(
            new SwarmPlan(swarmId, List.of()),
            Map.of("swarm", List.of(Map.of("time", "00:00:00", "action", "start"))));
    }
}
