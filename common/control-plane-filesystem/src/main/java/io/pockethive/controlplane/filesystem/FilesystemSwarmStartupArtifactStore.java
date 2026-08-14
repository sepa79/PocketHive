package io.pockethive.controlplane.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarm.model.SwarmStartupArtifactReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Objects;

/** Canonical persistence and verification for immutable swarm-startup artifacts. */
public final class FilesystemSwarmStartupArtifactStore {

  private final ObjectMapper mapper;
  private final RuntimeFilesystemLayout layout;

  public FilesystemSwarmStartupArtifactStore(ObjectMapper mapper, RuntimeFilesystemLayout layout) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.layout = Objects.requireNonNull(layout, "layout");
  }

  public SwarmStartupArtifactReference save(String swarmId, SwarmStartupArtifact artifact) {
    String resolvedSwarmId = RuntimeFilesystemLayout.requireSegment(swarmId, "swarmId");
    Objects.requireNonNull(artifact, "artifact");
    try {
      byte[] content = mapper.writeValueAsBytes(artifact);
      String sha256 = FilesystemDigest.sha256(content);
      String fileName = "startup-" + sha256 + ".json";
      Path artifactPath = layout.startupArtifactDirectory(resolvedSwarmId).resolve(fileName);
      Files.createDirectories(artifactPath.getParent());
      writeImmutable(artifactPath, content);
      return new SwarmStartupArtifactReference(
          layout.publishedStartupArtifact(resolvedSwarmId, fileName).toString(), sha256);
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to persist startup artifact for swarm " + resolvedSwarmId, exception);
    }
  }

  public SwarmStartupArtifact load(String artifactPath, String expectedSha256, String expectedSwarmId) {
    Path path = layout.requireLocalPath(artifactPath);
    String digest = FilesystemDigest.requireSha256(expectedSha256, "expectedSha256");
    String swarmId = RuntimeFilesystemLayout.requireSegment(expectedSwarmId, "expectedSwarmId");
    try {
      byte[] content = Files.readAllBytes(path);
      String actualSha256 = FilesystemDigest.sha256(content);
      if (!MessageDigest.isEqual(
          digest.getBytes(StandardCharsets.US_ASCII),
          actualSha256.getBytes(StandardCharsets.US_ASCII))) {
        throw new IllegalStateException(
            "Startup artifact checksum mismatch: expected " + digest + " but was " + actualSha256);
      }
      SwarmStartupArtifact artifact = mapper.readValue(content, SwarmStartupArtifact.class);
      if (!swarmId.equals(artifact.swarmPlan().id())) {
        throw new IllegalStateException(
            "Startup artifact swarm id mismatch: expected " + swarmId + " but was " + artifact.swarmPlan().id());
      }
      return artifact;
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to load startup artifact " + path, exception);
    }
  }

  private static void writeImmutable(Path path, byte[] content) throws IOException {
    if (Files.exists(path)) {
      if (!MessageDigest.isEqual(Files.readAllBytes(path), content)) {
        throw new IllegalStateException("Existing startup artifact does not match digest path: " + path);
      }
      return;
    }
    Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

}
