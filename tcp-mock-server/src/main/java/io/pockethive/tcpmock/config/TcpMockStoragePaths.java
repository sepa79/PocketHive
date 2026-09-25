package io.pockethive.tcpmock.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Responsibility: resolve snapshot locations from the explicit instance data directory. Must not:
 * read files, choose storage modes or mutate catalogue state. Contract:
 * RESP-TCP-MOCK-CATALOGUE-STORAGE —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-catalogue-storage.
 */
@Component
public final class TcpMockStoragePaths {
  private final Path directory;

  public TcpMockStoragePaths(@Value("${tcp-mock.data-directory}") Path directory) {
    if (directory == null || directory.toString().isBlank() || !directory.isAbsolute()) {
      throw new IllegalArgumentException("tcp-mock.data-directory must be an absolute path");
    }
    this.directory = directory.normalize();
  }

  public Path mappingCatalogue() {
    return directory.resolve("mapping-catalogue.json");
  }

  public Path workspaceCatalogue() {
    return directory.resolve("workspace-catalogue.json");
  }
}
