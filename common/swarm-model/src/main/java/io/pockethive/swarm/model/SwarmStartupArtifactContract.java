package io.pockethive.swarm.model;

/** Canonical constants for the filesystem swarm-startup artifact contract. */
public final class SwarmStartupArtifactContract {

    public static final String SCHEMA_V1 = "pockethive/swarm-startup/v1";
    public static final String CONTAINER_RUNTIME_ROOT = "/app/scenarios-runtime";
    public static final String WRITE_ROOT_ENV = "POCKETHIVE_STARTUP_ARTIFACT_WRITE_ROOT";
    public static final String READ_ROOT_ENV = "POCKETHIVE_STARTUP_ARTIFACT_READ_ROOT";
    public static final String PATH_ENV = "POCKETHIVE_STARTUP_ARTIFACT_PATH";
    public static final String SHA256_ENV = "POCKETHIVE_STARTUP_ARTIFACT_SHA256";

    private SwarmStartupArtifactContract() {
    }
}
