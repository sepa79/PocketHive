package io.pockethive.controlplane.filesystem;

import io.pockethive.swarm.model.RuntimeFilesystemContract;
import java.nio.file.Path;

/** Canonical validated host-to-container bind mount for the shared runtime filesystem. */
public record RuntimeFilesystemMount(Path hostRoot) {

  public RuntimeFilesystemMount {
    if (hostRoot == null || !hostRoot.isAbsolute()) {
      throw new IllegalStateException("host runtime root must be absolute");
    }
    hostRoot = hostRoot.normalize();
  }

  public static RuntimeFilesystemMount of(String hostRoot) {
    if (hostRoot == null || hostRoot.isBlank()) {
      throw new IllegalStateException(
          RuntimeFilesystemContract.HOST_ROOT_ENV + " must not be blank");
    }
    return new RuntimeFilesystemMount(Path.of(hostRoot.trim()));
  }

  public String volume() {
    return hostRoot + ":" + RuntimeFilesystemContract.CONTAINER_ROOT;
  }

  public String swarmVolume(String swarmId, String containerDestination, boolean readOnly) {
    if (containerDestination == null || containerDestination.isBlank()
        || !Path.of(containerDestination).isAbsolute()) {
      throw new IllegalArgumentException("container destination must be absolute");
    }
    String volume = hostRoot.resolve(RuntimeFilesystemLayout.requireSegment(swarmId, "swarmId"))
        + ":" + Path.of(containerDestination).normalize();
    return readOnly ? volume + ":ro" : volume;
  }
}
