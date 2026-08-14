package io.pockethive.controlplane.filesystem;

import java.nio.file.Path;
import java.util.Objects;

/** The sole path resolver for artifacts stored below the shared swarm runtime root. */
public final class RuntimeFilesystemLayout {

  private static final String STARTUP_ARTIFACTS = "runtime-artifacts";
  private static final String REMOVE_OPERATIONS = "operations/remove";

  private final Path localRoot;
  private final Path publishedRoot;

  private RuntimeFilesystemLayout(Path localRoot, Path publishedRoot) {
    this.localRoot = normalizeRoot(localRoot, "local runtime root");
    this.publishedRoot = normalizeRoot(publishedRoot, "published runtime root");
  }

  public static RuntimeFilesystemLayout of(String localRoot, String publishedRoot) {
    return new RuntimeFilesystemLayout(
        requiredPath(localRoot, "local runtime root"),
        requiredPath(publishedRoot, "published runtime root"));
  }

  public Path localRoot() {
    return localRoot;
  }

  public Path swarmRoot(String swarmId) {
    return inside(localRoot.resolve(requireSegment(swarmId, "swarmId")));
  }

  public Path startupArtifactDirectory(String swarmId) {
    return inside(swarmRoot(swarmId).resolve(STARTUP_ARTIFACTS));
  }

  public Path swarmRunDirectory(String swarmId, String runId) {
    return inside(swarmRoot(swarmId).resolve(requireSegment(runId, "runId")));
  }

  public Path publishedStartupArtifact(String swarmId, String fileName) {
    return publishedRoot.resolve(requireSegment(swarmId, "swarmId"))
        .resolve(STARTUP_ARTIFACTS)
        .resolve(requireSegment(fileName, "fileName"))
        .normalize();
  }

  public Path removeOperationDirectory(String swarmId, String correlationId) {
    return inside(swarmRoot(swarmId)
        .resolve(REMOVE_OPERATIONS)
        .resolve(requireSegment(correlationId, "correlationId")));
  }

  public Path requireLocalPath(String value) {
    Path path = normalizeRoot(requiredPath(value, "runtime artifact path"), "runtime artifact path");
    if (!path.startsWith(localRoot) || path.equals(localRoot)) {
      throw new IllegalStateException("Runtime artifact path must be inside runtime root " + localRoot);
    }
    return path;
  }

  public Path publishedRoot() {
    return publishedRoot;
  }

  private Path inside(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(localRoot) || normalized.equals(localRoot)) {
      throw new IllegalArgumentException("Runtime path escapes runtime root");
    }
    return normalized;
  }

  private static Path requiredPath(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(field + " must not be blank");
    }
    return Path.of(value.trim());
  }

  private static Path normalizeRoot(Path path, String field) {
    Path required = Objects.requireNonNull(path, field);
    if (!required.isAbsolute()) {
      throw new IllegalStateException(field + " must be absolute");
    }
    return required.normalize();
  }

  /** Validates and normalizes an identifier used as one filesystem path segment. */
  public static String requireSegment(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    String trimmed = value.trim();
    if (trimmed.contains("/") || trimmed.contains("\\") || ".".equals(trimmed) || "..".equals(trimmed)) {
      throw new IllegalArgumentException(field + " must be a single path segment");
    }
    return trimmed;
  }
}
