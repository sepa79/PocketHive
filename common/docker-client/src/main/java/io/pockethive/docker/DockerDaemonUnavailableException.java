package io.pockethive.docker;

/**
 * Indicates that the Docker daemon could not be reached from the current runtime.
 */
public class DockerDaemonUnavailableException extends RuntimeException {

    public DockerDaemonUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
