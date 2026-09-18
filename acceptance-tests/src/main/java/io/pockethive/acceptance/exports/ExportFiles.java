package io.pockethive.acceptance.exports;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Responsibility: read one worker's export files through the canonical runtime layout.
 * Must not: reconstruct directory names, create/delete files, infer success or discover mounts.
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files.
 */
public final class ExportFiles {
  private final Path root;
  private final Path directory;
  private final String temporarySuffix;

  public ExportFiles(RuntimeFilesystemLayout layout, String swarmId, String runId,
      String workerInstance, String temporarySuffix) {
    Objects.requireNonNull(layout, "layout");
    root = layout.localRoot();
    directory = layout.workerOutputDirectory(swarmId, runId, workerInstance);
    if (temporarySuffix == null || temporarySuffix.isBlank()) {
      throw new IllegalArgumentException("Explicit exporter temporary suffix is required");
    }
    this.temporarySuffix = temporarySuffix;
  }

  public ExportFilesSnapshot read() throws IOException {
    if (!Files.isDirectory(root) || !Files.isReadable(root)) {
      throw new IOException("Selected runtime root is unavailable: " + root);
    }
    var finalized = new LinkedHashMap<String, String>();
    var pending = new LinkedHashSet<String>();
    if (Files.notExists(directory)) return new ExportFilesSnapshot(finalized, pending);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Worker output is not a directory: " + directory);
    }
    readDirectory(directory, finalized, pending);
    return new ExportFilesSnapshot(finalized, pending);
  }

  private void readDirectory(Path current, java.util.Map<String, String> finalized,
      java.util.Set<String> pending) throws IOException {
    try (var entries = Files.newDirectoryStream(current)) {
      for (Path path : entries) {
        if (Files.isSymbolicLink(path)) throw new IOException("Unexpected output symlink: " + path);
        String name = directory.relativize(path).toString();
        // Inspect pending names before attributes: atomic finalization may remove the temp entry.
        if (name.endsWith(temporarySuffix)) {
          pending.add(name);
        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          readDirectory(path, finalized, pending);
        } else {
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output is not a regular file: " + path);
          }
          finalized.put(name, Files.readString(path));
        }
      }
    }
  }
}
