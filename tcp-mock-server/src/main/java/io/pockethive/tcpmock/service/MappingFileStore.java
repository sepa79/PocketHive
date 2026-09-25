package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.config.TcpMockStoragePaths;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility: read and atomically replace the durable runtime mapping snapshot. Must not:
 * select defaults, mutate the registry or suppress storage failures. Contract:
 * RESP-TCP-MOCK-MAPPING-FILES —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-files.
 */
@Component
public class MappingFileStore implements MappingPersistence {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AtomicSnapshotFile snapshot;

  @Autowired
  public MappingFileStore(TcpMockStoragePaths paths) {
    snapshot = new AtomicSnapshotFile(paths.mappingCatalogue());
  }

  MappingFileStore(Path dataRoot) {
    this(new TcpMockStoragePaths(dataRoot));
  }

  @Override
  public boolean hasSnapshot() {
    // An inaccessible path must proceed to load and fail, not masquerade as fresh state.
    return snapshot.exists();
  }

  @Override
  public List<MessageTypeMapping> load() {
    try {
      List<MessageTypeMapping> mappings =
          mapper.readValue(snapshot.read(), new TypeReference<>() {});
      if (mappings == null) {
        throw new IOException("Mapping catalogue must be a JSON array");
      }
      return mappings;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot load mapping catalogue: ", e);
    }
  }

  @Override
  public void save(Collection<MessageTypeMapping> mappings) {
    try {
      snapshot.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(mappings));
    } catch (IOException error) {
      throw new UncheckedIOException("Cannot encode snapshot", error);
    }
  }
}
