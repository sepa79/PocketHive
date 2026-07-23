package io.pockethive.controlplane.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Canonical immutable filesystem transport for the swarm-remove request/result handshake. */
public final class FilesystemSwarmRemoveStore {

  private final ObjectMapper mapper;
  private final Path runtimeRoot;

  public FilesystemSwarmRemoveStore(ObjectMapper mapper, Path runtimeRoot) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot").toAbsolutePath().normalize();
  }

  public Path saveRequest(RemoveRequest request) {
    Objects.requireNonNull(request, "request");
    Path path = operationDirectory(request.swarmId(), request.correlationId()).resolve("request.json");
    writeImmutable(path, request, RemoveRequest.class);
    return path;
  }

  public RemoveRequest loadRequest(String swarmId, String correlationId) {
    Path path = operationDirectory(swarmId, correlationId).resolve("request.json");
    try {
      return mapper.readValue(path.toFile(), RemoveRequest.class);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to load remove request: " + path, exception);
    }
  }

  public void saveResult(RemoveResult result) {
    Objects.requireNonNull(result, "result");
    Path path = operationDirectory(result.swarmId(), result.correlationId()).resolve("result.json");
    writeImmutable(path, result, RemoveResult.class);
  }

  public Optional<RemoveResult> findResult(String swarmId, String correlationId) {
    Path path = operationDirectory(swarmId, correlationId).resolve("result.json");
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(path.toFile(), RemoveResult.class));
    } catch (IOException exception) {
      throw new IllegalStateException("Invalid remove result: " + path, exception);
    }
  }

  public void deleteSwarmRuntime(String swarmId) {
    Path swarmRoot = runtimeRoot.resolve(segment(swarmId, "swarmId")).normalize();
    if (!swarmRoot.startsWith(runtimeRoot) || swarmRoot.equals(runtimeRoot)) {
      throw new IllegalArgumentException("Swarm runtime path escapes runtime root");
    }
    if (!Files.exists(swarmRoot)) {
      return;
    }
    try (var paths = Files.walk(swarmRoot)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to delete swarm runtime: " + swarmRoot, exception);
    }
  }

  private <T> void writeImmutable(Path path, T value, Class<T> type) {
    try {
      Files.createDirectories(path.getParent());
      byte[] bytes = mapper.writeValueAsBytes(value);
      if (Files.exists(path)) {
        T existing = mapper.readValue(path.toFile(), type);
        if (!existing.equals(value)) {
          throw new IllegalStateException("Immutable remove artifact differs: " + path);
        }
        return;
      }
      Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to write remove artifact: " + path, exception);
    }
  }

  private Path operationDirectory(String swarmId, String correlationId) {
    Path directory = runtimeRoot.resolve(segment(swarmId, "swarmId"))
        .resolve("operations/remove")
        .resolve(segment(correlationId, "correlationId"))
        .normalize();
    if (!directory.startsWith(runtimeRoot)) {
      throw new IllegalArgumentException("Remove path escapes runtime root");
    }
    return directory;
  }

  private static String segment(String value, String field) {
    if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
        || ".".equals(value) || "..".equals(value)) {
      throw new IllegalArgumentException(field + " must be a safe path segment");
    }
    return value.trim();
  }
}
