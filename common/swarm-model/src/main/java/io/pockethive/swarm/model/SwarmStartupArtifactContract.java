package io.pockethive.swarm.model;

/** Canonical constants for the filesystem swarm-startup artifact contract. */
public final class SwarmStartupArtifactContract {

    public static final String SCHEMA_V1 = "pockethive/swarm-startup/v1";
    public static final String PATH_ENV = "POCKETHIVE_STARTUP_ARTIFACT_PATH";
    public static final String SHA256_ENV = "POCKETHIVE_STARTUP_ARTIFACT_SHA256";

    private SwarmStartupArtifactContract() {
    }
}
