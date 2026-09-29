package io.pockethive.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.pockethive.docker.compute.DockerSingleNodeComputeAdapter;
import io.pockethive.docker.compute.DockerSwarmServiceComputeAdapter;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.ports.ComputeHost;
import io.pockethive.manager.runtime.ComputeAdapterType;
import java.io.IOException;
import java.util.Objects;

/**
 * Responsibility: own an application Docker connection and compose its operation implementations.
 * Must not: decide lifecycle state or change the two established compute selection policies.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public final class DockerEngine implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DockerEngine.class);
    private final DockerClient client;
    private final DockerContainerClient containers;

    DockerEngine(DockerClient client) {
        this.client = Objects.requireNonNull(client, "client");
        this.containers = new DockerContainerClient(client);
    }

    public static DockerEngine fromEnvironment() {
        return new DockerEngine(DockerConnections.open(DockerConnections.environment()));
    }

    public static DockerEngine forController(String host, String socketPath) {
        return new DockerEngine(DockerConnections.open(DockerConnections.controller(host, socketPath)));
    }

    public ComputeHost host() {
        return containers;
    }

    public DockerRuntimeClient runtime(ObjectMapper mapper) {
        return new DockerRuntimeClient(client, mapper);
    }

    public ComputeAdapter orchestratorAdapter(ComputeAdapterType configured) {
        ComputeAdapterType resolved = configured;
        if (configured == null || configured == ComputeAdapterType.AUTO) {
            resolved = isSwarmManager() ? ComputeAdapterType.SWARM_STACK : ComputeAdapterType.DOCKER_SINGLE;
        } else if (configured == ComputeAdapterType.SWARM_STACK && !isSwarmManager()) {
            throw new IllegalStateException(
                "Compute adapter configured as SWARM_STACK but this Docker engine is not a Swarm manager. "
                    + "Run orchestrator against a Swarm manager node or use DOCKER_SINGLE/AUTO.");
        }
        log.info("Using compute adapter type {} for orchestrator (configured as {})", resolved, configured);
        return createAdapter(resolved);
    }

    public ComputeAdapter controllerAdapter(ComputeAdapterType configured) {
        return createAdapter(ComputeAdapterType.defaulted(configured));
    }

    private ComputeAdapter createAdapter(ComputeAdapterType type) {
        return switch (type) {
            case DOCKER_SINGLE -> new DockerSingleNodeComputeAdapter(containers);
            case SWARM_STACK -> new DockerSwarmServiceComputeAdapter(client, containers::resolveControlNetwork);
            case AUTO -> throw new IllegalStateException("Unsupported compute adapter type: " + type);
        };
    }

    private boolean isSwarmManager() {
        var swarm = client.infoCmd().exec().getSwarm();
        if (swarm == null) {
            return false;
        }
        var state = swarm.getLocalNodeState();
        return state != null && state.name().equalsIgnoreCase("active")
            && Boolean.TRUE.equals(swarm.getControlAvailable());
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
