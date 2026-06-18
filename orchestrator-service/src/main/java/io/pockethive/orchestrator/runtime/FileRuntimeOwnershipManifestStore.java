package io.pockethive.orchestrator.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileRuntimeOwnershipManifestStore implements RuntimeOwnershipManifestStore {
    public static final String MANIFEST_FILE = "runtime-ownership-manifest.json";

    private final ObjectMapper mapper;
    private final Path root;

    public FileRuntimeOwnershipManifestStore(
        ObjectMapper mapper,
        @Value("${POCKETHIVE_SCENARIOS_RUNTIME_ROOT:scenarios-runtime}") String root) {
        this.mapper = mapper;
        this.root = Path.of(root == null || root.isBlank() ? "scenarios-runtime" : root.trim());
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
        Path swarmDir = safeRoot().resolve(safeSegment(swarmId));
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
        return safeRoot()
            .resolve(safeSegment(swarmId))
            .resolve(safeSegment(runId))
            .resolve(MANIFEST_FILE);
    }

    private Path safeRoot() {
        return root.toAbsolutePath().normalize();
    }

    private static String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("manifest path segment must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("manifest path segment is not safe: " + value);
        }
        return trimmed;
    }
}
