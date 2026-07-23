package io.pockethive.controlplane.filesystem;

import io.pockethive.swarm.model.SwarmStartupArtifact;
import java.util.Objects;

/** A configured read handle for the one startup artifact assigned to a controller. */
public final class FilesystemSwarmStartupArtifactLoader {

  private final FilesystemSwarmStartupArtifactStore store;
  private final String artifactPath;
  private final String expectedSha256;

  public FilesystemSwarmStartupArtifactLoader(
      FilesystemSwarmStartupArtifactStore store,
      String artifactPath,
      String expectedSha256) {
    this.store = Objects.requireNonNull(store, "store");
    if (artifactPath == null || artifactPath.isBlank()) {
      throw new IllegalStateException("startup artifact path must not be blank");
    }
    this.artifactPath = artifactPath.trim();
    this.expectedSha256 = FilesystemDigest.requireSha256(expectedSha256, "expectedSha256");
  }

  public SwarmStartupArtifact load(String expectedSwarmId) {
    return store.load(artifactPath, expectedSha256, expectedSwarmId);
  }

  public String expectedSha256() {
    return expectedSha256;
  }
}
