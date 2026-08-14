package io.pockethive.swarm.model;

/** Canonical configuration names and container destination for shared runtime files. */
public final class RuntimeFilesystemContract {

  /** Absolute root visible to the process performing filesystem IO. */
  public static final String LOCAL_ROOT_ENV = "POCKETHIVE_RUNTIME_FILESYSTEM_ROOT";

  /** Absolute host source used only when the runtime adapter creates bind mounts. */
  public static final String HOST_ROOT_ENV = "POCKETHIVE_SCENARIOS_RUNTIME_ROOT";

  public static final String CONTAINER_ROOT = "/app/scenarios-runtime";

  private RuntimeFilesystemContract() {
  }
}
