package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.pockethive.tcpmock.config.TcpMockStoragePaths;
import io.pockethive.tcpmock.model.WorkspaceCatalogue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility: encode workspace snapshots and fence writes after a storage failure. Must not:
 * create workspace identities, choose defaults or publish application state. Contract:
 * RESP-TCP-MOCK-CATALOGUE-STORAGE —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-catalogue-storage.
 */
@Component
public final class WorkspaceFileStore implements WorkspacePersistence {
  private final JsonMapper mapper =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final AtomicSnapshotFile snapshot;
  private boolean failed;

  @Autowired
  public WorkspaceFileStore(TcpMockStoragePaths paths) {
    snapshot = new AtomicSnapshotFile(paths.workspaceCatalogue());
  }

  public WorkspaceFileStore(Path dataRoot) {
    this(new TcpMockStoragePaths(dataRoot));
  }

  @Override
  public boolean hasSnapshot() {
    return snapshot.exists();
  }

  @Override
  public WorkspaceCatalogue load() {
    try {
      WorkspaceCatalogue catalogue = mapper.readValue(snapshot.read(), WorkspaceCatalogue.class);
      if (catalogue == null || catalogue.version() != WorkspaceCatalogue.VERSION) {
        throw new IOException("Unsupported workspace catalogue version");
      }
      return catalogue;
    } catch (IOException error) {
      throw new UncheckedIOException("Cannot load workspace catalogue", error);
    }
  }

  @Override
  public synchronized void save(WorkspaceCatalogue catalogue) {
    if (failed)
      throw new IllegalStateException("Workspace storage failed; restore storage and restart");
    try {
      snapshot.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(catalogue));
    } catch (IOException error) {
      failed = true;
      throw new UncheckedIOException("Cannot encode workspace catalogue", error);
    } catch (RuntimeException error) {
      failed = true;
      throw error;
    }
  }
}
