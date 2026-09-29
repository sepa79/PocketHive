package io.pockethive.controlplane.filesystem;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Responsibility: resolve relative output files within an immutable runtime directory projection.
 * Must not: determine swarm/run layout, perform IO or accept absolute/escaping file paths.
 * Contract: RESP-RUNTIME-FILESYSTEM-LAYOUT — docs/architecture/runtime-responsibilities.md#resp-runtime-filesystem-layout.
 */
public record RuntimeOutputDirectory(Path path) {
  public RuntimeOutputDirectory {
    Objects.requireNonNull(path, "output directory");
    if (!path.isAbsolute()) throw new IllegalArgumentException("Output directory must be absolute");
    path = path.normalize();
  }

  public Path file(String relativeName) {
    if (relativeName == null || relativeName.isBlank()) {
      throw new IllegalArgumentException("Output file name must not be blank");
    }
    Path relative = Path.of(relativeName);
    Path result = path.resolve(relative).normalize();
    if (relative.isAbsolute() || !result.startsWith(path) || result.equals(path)) {
      throw new IllegalArgumentException("Output file must be inside worker output directory");
    }
    return result;
  }
}
