package io.pockethive.acceptance.exports;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportFilesTest {
  @TempDir Path root;
  private RuntimeFilesystemLayout layout() { return RuntimeFilesystemLayout.of(root.toString(), "/runtime"); }
  private ExportFiles observer() { return new ExportFiles(layout(), "swarm", "run", "worker", ".tmp"); }

  @Test void observesOnlySelectedWorkerAndFollowsFinalizationWithoutChangingFiles() throws Exception {
    var layout = layout();
    var observer = observer();
    assertEquals(Map.of(), observer.read().finalized());
    assertFalse(Files.exists(layout.swarmRoot("swarm")));
    Path output = Files.createDirectories(layout.workerOutputDirectory("swarm", "run", "worker"));
    Path other = Files.createDirectories(layout.workerOutputDirectory("swarm", "other-run", "worker"));
    Files.writeString(other.resolve("unrelated.txt"), "wrong run");
    Files.writeString(output.resolve("one.txt"), "H\nD|one\nT|1\n");
    // Deliberately invalid UTF-8: pending data must never be decoded as finalized output.
    Path pending = Files.write(output.resolve("two.txt.tmp"), new byte[]{(byte) 0xff});
    var first = observer.read();
    assertEquals(Map.of("one.txt", "H\nD|one\nT|1\n"), first.finalized());
    assertEquals(Set.of("two.txt.tmp"), first.pending());
    Files.writeString(pending, "H\nD|two\nT|1\n");
    Files.move(pending, output.resolve("two.txt"));
    var second = observer.read();
    assertEquals(2, second.finalized().size());
    assertTrue(second.pending().isEmpty());
    assertEquals(1, first.finalized().size());
    assertTrue(Files.exists(output.resolve("two.txt")));
  }

  @Test void unavailableRootAndInvalidOutputAreErrorsNotEmptySuccess() throws Exception {
    Path missing = root.resolve("missing");
    var observer = new ExportFiles(RuntimeFilesystemLayout.of(missing.toString(), "/runtime"),
        "swarm", "run", "worker", ".tmp");
    assertThrows(IOException.class, observer::read);
    Path output = layout().workerOutputDirectory("swarm", "run", "worker");
    Files.createDirectories(output.getParent());
    Files.writeString(output, "not a directory");
    assertThrows(IOException.class, observer()::read);
  }

  @Test void rejectsSymlinkInsteadOfReadingAnotherWorkersFile() throws Exception {
    Path output = Files.createDirectories(layout().workerOutputDirectory("swarm", "run", "worker"));
    Path foreign = Files.writeString(root.resolve("foreign.txt"), "wrong worker");
    Files.createSymbolicLink(output.resolve("foreign.txt"), foreign);
    assertThrows(IOException.class, observer()::read);
  }
}
