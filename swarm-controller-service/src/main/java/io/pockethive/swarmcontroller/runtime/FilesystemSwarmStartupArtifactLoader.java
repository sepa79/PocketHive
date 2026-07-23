package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Loads and verifies the one explicit startup artifact configured for this controller. */
@Component
public final class FilesystemSwarmStartupArtifactLoader {

    private final ObjectMapper mapper;
    private final Path artifactPath;
    private final String expectedSha256;

    @Autowired
    public FilesystemSwarmStartupArtifactLoader(
        ObjectMapper mapper,
        @Value("${" + SwarmStartupArtifactContract.PATH_ENV + ":}") String artifactPath,
        @Value("${" + SwarmStartupArtifactContract.SHA256_ENV + ":}") String expectedSha256,
        @Value("${" + SwarmStartupArtifactContract.READ_ROOT_ENV + ":"
            + SwarmStartupArtifactContract.CONTAINER_RUNTIME_ROOT + "}") String allowedRoot) {
        this(mapper, artifactPath, expectedSha256, Path.of(allowedRoot));
    }

    FilesystemSwarmStartupArtifactLoader(ObjectMapper mapper,
                                         String artifactPath,
                                         String expectedSha256,
                                         Path allowedRoot) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalStateException(SwarmStartupArtifactContract.PATH_ENV + " must not be blank");
        }
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                SwarmStartupArtifactContract.SHA256_ENV + " must be a lowercase SHA-256 digest");
        }
        Path resolvedPath = Path.of(artifactPath).toAbsolutePath().normalize();
        Path resolvedAllowedRoot = Objects.requireNonNull(allowedRoot, "allowedRoot").toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(resolvedAllowedRoot)) {
            throw new IllegalStateException("Startup artifact must be inside " + resolvedAllowedRoot);
        }
        this.artifactPath = resolvedPath;
        this.expectedSha256 = expectedSha256;
    }

    public SwarmStartupArtifact load(String expectedSwarmId) {
        if (expectedSwarmId == null || expectedSwarmId.isBlank()) {
            throw new IllegalArgumentException("expectedSwarmId must not be blank");
        }
        try {
            byte[] content = Files.readAllBytes(artifactPath);
            String actualSha256 = sha256(content);
            if (!MessageDigest.isEqual(
                expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actualSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IllegalStateException(
                    "Startup artifact checksum mismatch: expected " + expectedSha256 + " but was " + actualSha256);
            }
            SwarmStartupArtifact artifact = mapper.readValue(content, SwarmStartupArtifact.class);
            String artifactSwarmId = artifact.swarmPlan().id();
            if (!expectedSwarmId.trim().equals(artifactSwarmId)) {
                throw new IllegalStateException(
                    "Startup artifact swarm id mismatch: expected " + expectedSwarmId + " but was " + artifactSwarmId);
            }
            return artifact;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load startup artifact " + artifactPath, e);
        }
    }

    public String expectedSha256() {
        return expectedSha256;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
