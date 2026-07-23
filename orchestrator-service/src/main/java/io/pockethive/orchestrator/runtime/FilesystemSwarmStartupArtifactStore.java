package io.pockethive.orchestrator.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import io.pockethive.swarm.model.SwarmStartupArtifactReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Persists immutable swarm-startup artifacts in the deployment-local shared directory. */
@Component
public final class FilesystemSwarmStartupArtifactStore {

    private static final String ARTIFACT_DIRECTORY = "runtime-artifacts";

    private final ObjectMapper mapper;
    private final Path runtimeRoot;

    public FilesystemSwarmStartupArtifactStore(
        ObjectMapper mapper,
        @Value("${" + SwarmStartupArtifactContract.WRITE_ROOT_ENV + ":}") String runtimeRoot) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (runtimeRoot == null || runtimeRoot.isBlank()) {
            throw new IllegalStateException(SwarmStartupArtifactContract.WRITE_ROOT_ENV + " must not be blank");
        }
        this.runtimeRoot = Path.of(runtimeRoot).toAbsolutePath().normalize();
    }

    public SwarmStartupArtifactReference save(String swarmId, SwarmStartupArtifact artifact) {
        String resolvedSwarmId = requirePathSegment(swarmId, "swarmId");
        Objects.requireNonNull(artifact, "artifact");
        try {
            byte[] content = mapper.writeValueAsBytes(artifact);
            String sha256 = sha256(content);
            Path directory = runtimeRoot.resolve(resolvedSwarmId).resolve(ARTIFACT_DIRECTORY).normalize();
            requireWithinRuntimeRoot(directory);
            Files.createDirectories(directory);
            String fileName = "startup-" + sha256 + ".json";
            Path artifactPath = directory.resolve(fileName).normalize();
            requireWithinRuntimeRoot(artifactPath);
            if (Files.exists(artifactPath)) {
                byte[] existing = Files.readAllBytes(artifactPath);
                if (!MessageDigest.isEqual(existing, content)) {
                    throw new IllegalStateException("Existing startup artifact does not match digest path: " + artifactPath);
                }
            } else {
                Files.write(artifactPath, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            String containerPath = SwarmStartupArtifactContract.CONTAINER_RUNTIME_ROOT
                + "/" + resolvedSwarmId + "/"
                + ARTIFACT_DIRECTORY + "/" + fileName;
            return new SwarmStartupArtifactReference(containerPath, sha256);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist startup artifact for swarm " + resolvedSwarmId, e);
        }
    }

    private void requireWithinRuntimeRoot(Path path) {
        if (!path.startsWith(runtimeRoot)) {
            throw new IllegalArgumentException("Startup artifact path escapes runtime root: " + path);
        }
    }

    private static String requirePathSegment(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException(field + " must be a single path segment");
        }
        return trimmed;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

}
