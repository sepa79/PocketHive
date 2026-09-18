package io.pockethive.controlplane.filesystem;

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

  @TempDir Path root;
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void writesAndLoadsOneContentAddressedArtifactContract() throws Exception {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(root.toString(), "/runtime");
    FilesystemSwarmStartupArtifactStore store =
        new FilesystemSwarmStartupArtifactStore(mapper, layout);
    SwarmStartupArtifact artifact = artifact("alpha");

    var reference = store.save("alpha", artifact);
    Path local = layout.startupArtifactDirectory("alpha").resolve(Path.of(reference.path()).getFileName());

    assertThat(reference.path()).isEqualTo(
        "/runtime/alpha/runtime-artifacts/startup-" + reference.sha256() + ".json");
    assertThat(store.load(local.toString(), reference.sha256(), "alpha"))
        .isEqualTo(artifact);
    assertThat(Files.readAllBytes(local)).isNotEmpty();
  }

  @Test
  void rejectsChecksumMismatchWithoutAnotherReadPath() throws Exception {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(root.toString(), "/runtime");
    FilesystemSwarmStartupArtifactStore store =
        new FilesystemSwarmStartupArtifactStore(mapper, layout);
    var reference = store.save("alpha", artifact("alpha"));
    Path local = layout.startupArtifactDirectory("alpha").resolve(Path.of(reference.path()).getFileName());

    assertThatThrownBy(() -> store.load(
        local.toString(), "0".repeat(64), "alpha"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checksum mismatch");
  }

  @Test
  void configuredLoaderRejectsInvalidDigestBeforeReading() {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(root.toString(), "/runtime");
    FilesystemSwarmStartupArtifactStore store =
        new FilesystemSwarmStartupArtifactStore(mapper, layout);

    assertThatThrownBy(() -> new FilesystemSwarmStartupArtifactLoader(
        store, root.resolve("alpha/startup.json").toString(), "invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lowercase SHA-256");
  }

  private static SwarmStartupArtifact artifact(String swarmId) {
    return SwarmStartupArtifact.v1(new SwarmPlan(swarmId, List.of()), Map.of("bee", List.of()));
  }
}
