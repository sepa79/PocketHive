package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSwarmStartupArtifactLoaderTest {

    @TempDir
    Path runtimeRoot;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiesChecksumAndSwarmIdentity() throws Exception {
        SwarmStartupArtifact expected = artifact("swarm-a");
        byte[] content = mapper.writeValueAsBytes(expected);
        Path artifactPath = runtimeRoot.resolve("startup.json");
        Files.write(artifactPath, content);

        FilesystemSwarmStartupArtifactLoader loader = new FilesystemSwarmStartupArtifactLoader(
            mapper, artifactPath.toString(), sha256(content), runtimeRoot);

        assertThat(loader.load("swarm-a")).isEqualTo(expected);
    }

    @Test
    void rejectsChecksumMismatchWithoutFallback() throws Exception {
        byte[] content = mapper.writeValueAsBytes(artifact("swarm-a"));
        Path artifactPath = runtimeRoot.resolve("startup.json");
        Files.write(artifactPath, content);
        FilesystemSwarmStartupArtifactLoader loader = new FilesystemSwarmStartupArtifactLoader(
            mapper, artifactPath.toString(), "0".repeat(64), runtimeRoot);

        assertThatThrownBy(() -> loader.load("swarm-a"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void rejectsArtifactForAnotherSwarm() throws Exception {
        byte[] content = mapper.writeValueAsBytes(artifact("swarm-b"));
        Path artifactPath = runtimeRoot.resolve("startup.json");
        Files.write(artifactPath, content);
        FilesystemSwarmStartupArtifactLoader loader = new FilesystemSwarmStartupArtifactLoader(
            mapper, artifactPath.toString(), sha256(content), runtimeRoot);

        assertThatThrownBy(() -> loader.load("swarm-a"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("swarm id mismatch");
    }

    private static SwarmStartupArtifact artifact(String swarmId) {
        return SwarmStartupArtifact.v1(new SwarmPlan(swarmId, List.of()), Map.of("bee", List.of()));
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
