package io.pockethive.controlplane.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.RuntimeFilesystemContract;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeFilesystemLayoutTest {

  @TempDir Path localRoot;

  @Test
  void derivesEveryRuntimePathFromOneValidatedLayout() {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(
        localRoot.toString(), RuntimeFilesystemContract.CONTAINER_ROOT);

    assertThat(layout.startupArtifactDirectory("alpha"))
        .isEqualTo(localRoot.resolve("alpha/runtime-artifacts"));
    assertThat(layout.publishedStartupArtifact("alpha", "startup-a.json"))
        .isEqualTo(Path.of(RuntimeFilesystemContract.CONTAINER_ROOT + "/alpha/runtime-artifacts/startup-a.json"));
    assertThat(layout.removeOperationDirectory("alpha", "corr-1"))
        .isEqualTo(localRoot.resolve("alpha/operations/remove/corr-1"));
    assertThat(layout.swarmRunDirectory("alpha", "run-1"))
        .isEqualTo(localRoot.resolve("alpha/run-1"));
    assertThat(layout.swarmJournalFile("alpha", "run-1"))
        .isEqualTo(localRoot.resolve("alpha/run-1/journal.ndjson")).doesNotExist();
    assertThat(layout.swarmRoot("alpha")).isEqualTo(localRoot.resolve("alpha"));
  }

  @Test
  void rejectsMissingRootsAndUnsafeSegmentsWithoutFallback() {
    assertThatThrownBy(() -> RuntimeFilesystemLayout.of(" ", RuntimeFilesystemContract.CONTAINER_ROOT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local runtime root");
    assertThatThrownBy(() -> RuntimeFilesystemLayout.of("relative", RuntimeFilesystemContract.CONTAINER_ROOT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be absolute");

    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(
        localRoot.toString(), RuntimeFilesystemContract.CONTAINER_ROOT);
    assertThatThrownBy(() -> layout.swarmRoot("../outside"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("single path segment");
  }

  @Test
  void journalUsesTheSameIdentifierValidationAndRunIsolation() {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(localRoot.toString(), "/runtime");
    assertThat(layout.swarmJournalFile(" alpha ", " run-1 "))
        .isEqualTo(layout.swarmJournalFile("alpha", "run-1"));
    assertThat(layout.swarmJournalFile("alpha", "run-2"))
        .isNotEqualTo(layout.swarmJournalFile("alpha", "run-1"));
    for (String invalid : new String[] {"", "..", "../run", "/outside", "a/b", "a\\b"}) {
      assertThatThrownBy(() -> layout.swarmJournalFile("alpha", invalid))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> layout.swarmJournalFile(invalid, "run-1"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void projectsIsolatedWorkerOutputsToHostAndContainerWithoutCreatingDirectories() {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(localRoot.toString(), "/runtime");
    Path first = layout.workerOutputDirectory("alpha", "run-1", "exporter-1");
    assertThat(first).isEqualTo(localRoot.resolve("alpha/run-1/outputs/exporter-1")).doesNotExist();
    assertThat(layout.publishedWorkerOutputDirectory("alpha", "run-1", "exporter-1"))
        .isEqualTo(Path.of("/runtime/alpha/run-1/outputs/exporter-1"));
    assertThat(layout.workerOutputDirectory("alpha", "run-2", "exporter-1")).isNotEqualTo(first);
    assertThat(layout.workerOutputDirectory("alpha", "run-1", "exporter-2")).isNotEqualTo(first);
    assertThat(layout.workerOutputDirectory("beta", "run-1", "exporter-1")).isNotEqualTo(first);
    assertThatThrownBy(() -> layout.workerOutputDirectory("alpha", "run-1", "../other"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> layout.publishedWorkerOutputDirectory("alpha", "../run", "exporter-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsOnlyPathsInsideItsLocalRoot() {
    RuntimeFilesystemLayout layout = RuntimeFilesystemLayout.of(
        localRoot.toString(), RuntimeFilesystemContract.CONTAINER_ROOT);

    assertThat(layout.requireLocalPath(localRoot.resolve("alpha/startup.json").toString()))
        .isEqualTo(localRoot.resolve("alpha/startup.json"));
    assertThatThrownBy(() -> layout.requireLocalPath(localRoot.resolveSibling("outside.json").toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inside runtime root");
  }

  @Test
  void createsOneValidatedBindMountContract() {
    RuntimeFilesystemMount mount = RuntimeFilesystemMount.of("/opt/pockethive/scenarios-runtime");

    assertThat(mount.volume())
        .isEqualTo("/opt/pockethive/scenarios-runtime:" + RuntimeFilesystemContract.CONTAINER_ROOT);
    assertThat(mount.swarmVolume("alpha", "/app/scenario", true))
        .isEqualTo("/opt/pockethive/scenarios-runtime/alpha:/app/scenario:ro");
    assertThatThrownBy(() -> RuntimeFilesystemMount.of("relative"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> mount.swarmVolume("../outside", "/app/scenario", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("single path segment");
  }
}
