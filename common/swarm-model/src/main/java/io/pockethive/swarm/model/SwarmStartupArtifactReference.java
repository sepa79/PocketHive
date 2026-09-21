package io.pockethive.swarm.model;

/** Exact filesystem location and digest selected for one swarm-controller startup. */
public record SwarmStartupArtifactReference(String path, String sha256) {

    public SwarmStartupArtifactReference {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        path = path.trim();
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
        }
    }
}
