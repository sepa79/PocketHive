package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Controller side of the immutable filesystem remove handshake. */
@Component
public final class FilesystemSwarmRemoveStore {

  private final ObjectMapper mapper;
  private final Path runtimeRoot;

  @Autowired
  public FilesystemSwarmRemoveStore(ObjectMapper mapper) {
    this(mapper, Path.of(SwarmStartupArtifactContract.CONTAINER_RUNTIME_ROOT));
  }

  FilesystemSwarmRemoveStore(ObjectMapper mapper, Path runtimeRoot) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot").toAbsolutePath().normalize();
  }

  public RemoveRequest loadRequest(String swarmId, String correlationId) {
    Path path = directory(swarmId, correlationId).resolve("request.json");
    try {
      return mapper.readValue(path.toFile(), RemoveRequest.class);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to load remove request: " + path, exception);
    }
  }

  public void saveResult(RemoveResult result) {
    Objects.requireNonNull(result, "result");
    Path path = directory(result.swarmId(), result.correlationId()).resolve("result.json");
    try {
      byte[] bytes = mapper.writeValueAsBytes(result);
      if (Files.exists(path)) {
        RemoveResult existing = mapper.readValue(path.toFile(), RemoveResult.class);
        if (!existing.equals(result)) {
          throw new IllegalStateException("Immutable remove result differs: " + path);
        }
        return;
      }
      Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to write remove result: " + path, exception);
    }
  }

  public Optional<RemoveResult> findResult(String swarmId, String correlationId) {
    Path path = directory(swarmId, correlationId).resolve("result.json");
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(path.toFile(), RemoveResult.class));
    } catch (IOException exception) {
      throw new IllegalStateException("Invalid remove result: " + path, exception);
    }
  }

  private Path directory(String swarmId, String correlationId) {
    Path path = runtimeRoot.resolve(segment(swarmId, "swarmId"))
        .resolve("operations/remove")
        .resolve(segment(correlationId, "correlationId"))
        .normalize();
    if (!path.startsWith(runtimeRoot)) {
      throw new IllegalArgumentException("Remove path escapes runtime root");
    }
    return path;
  }

  private static String segment(String value, String field) {
    if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
        || ".".equals(value) || "..".equals(value)) {
      throw new IllegalArgumentException(field + " must be a safe path segment");
    }
    return value.trim();
  }
}
