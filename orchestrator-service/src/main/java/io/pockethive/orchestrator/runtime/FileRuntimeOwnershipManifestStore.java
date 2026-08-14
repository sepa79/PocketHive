package io.pockethive.orchestrator.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class FileRuntimeOwnershipManifestStore implements RuntimeOwnershipManifestStore {
    public static final String MANIFEST_FILE = "runtime-ownership-manifest.json";

    private final ObjectMapper mapper;
    private final RuntimeFilesystemLayout layout;

    public FileRuntimeOwnershipManifestStore(
        ObjectMapper mapper,
        RuntimeFilesystemLayout layout) {
        this.mapper = mapper;
        this.layout = layout;
    }

    @Override
    public void save(RuntimeOwnershipManifest manifest) {
        if (manifest == null) {
            return;
        }
        Path file = manifestPath(manifest.swarmId(), manifest.runId());
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write runtime ownership manifest " + file, ex);
        }
    }

    @Override
    public Optional<RuntimeOwnershipManifest> find(String swarmId, String runId) {
        Path file = manifestPath(swarmId, runId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), RuntimeOwnershipManifest.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read runtime ownership manifest " + file, ex);
        }
    }

    @Override
    public Optional<RuntimeOwnershipManifest> findLatest(String swarmId) {
        Path swarmDir = layout.swarmRoot(swarmId);
        if (!Files.isDirectory(swarmDir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(swarmDir)) {
            return stream
                .map(path -> path.resolve(MANIFEST_FILE))
                .filter(Files::isRegularFile)
                .map(this::readManifest)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(manifest -> manifest.createdAt() == null ? Instant.EPOCH : manifest.createdAt()));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list runtime ownership manifests for swarm " + swarmId, ex);
        }
    }

    private Optional<RuntimeOwnershipManifest> readManifest(Path file) {
        try {
            return Optional.of(mapper.readValue(file.toFile(), RuntimeOwnershipManifest.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read runtime ownership manifest " + file, ex);
        }
    }

    private Path manifestPath(String swarmId, String runId) {
        return layout.swarmRunDirectory(swarmId, runId).resolve(MANIFEST_FILE);
    }
}
