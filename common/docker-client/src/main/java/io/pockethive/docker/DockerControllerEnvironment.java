package io.pockethive.docker;

import io.pockethive.manager.runtime.ComputeAdapterType;
import java.util.Map;

/**
 * Responsibility: encode Docker controller launch settings and socket mounts.
 * Must not: choose compute mode or export Control Plane or Work settings.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public final class DockerControllerEnvironment {
    public static final String PLACEMENT_CONSTRAINTS_ENV = "POCKETHIVE_DOCKER_SWARM_PLACEMENT_CONSTRAINTS";

    private DockerControllerEnvironment() {
    }

    public static Map<String, String> encode(String socketPath, ComputeAdapterType type) {
        return Map.of(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", socketPath,
            "DOCKER_SOCKET_PATH", socketPath,
            "DOCKER_HOST", socketHost(socketPath),
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_COMPUTE_ADAPTER", type.name());
    }

    static String socketHost(String socketPath) {
        return "unix://" + socketPath;
    }

    public static String socketMount(String socketPath) {
        return socketPath + ":" + socketPath;
    }
}
