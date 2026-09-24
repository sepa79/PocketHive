package io.pockethive.manager.ports;

/**
 * Responsibility: expose host network discovery and image availability operations.
 * Must not: expose Docker SDK types or choose lifecycle transitions.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public interface ComputeHost {
    String resolveControlNetwork();

    void pullImage(String image);
}
