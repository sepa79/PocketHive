package io.pockethive.orchestrator.infra.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.docker.compute.DockerSingleNodeComputeAdapter;
import io.pockethive.docker.compute.DockerSwarmServiceComputeAdapter;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(DockerConfiguration.class);
    @Bean
    public DockerClient dockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean
    public DockerContainerClient dockerContainerClient(DockerClient dockerClient) {
        return new DockerContainerClient(dockerClient);
    }

    @Bean
    public ComputeAdapter computeAdapter(DockerClient dockerClient,
                                         DockerContainerClient dockerContainerClient,
                                         OrchestratorProperties properties) {
        ComputeAdapterType configured = properties.getDocker() == null
            ? ComputeAdapterType.AUTO
            : properties.getDocker().getComputeAdapter();
        ComputeAdapterType resolved = resolveAdapterType(dockerClient, configured);
        log.info("Using compute adapter type {} for orchestrator (configured as {})", resolved, configured);
        return switch (resolved) {
            case DOCKER_SINGLE -> new DockerSingleNodeComputeAdapter(dockerContainerClient);
            case SWARM_STACK ->
                new DockerSwarmServiceComputeAdapter(dockerClient, dockerContainerClient::resolveControlNetwork);
            case AUTO -> throw new IllegalStateException("AUTO must be resolved to a concrete adapter type");
        };
    }

    private ComputeAdapterType resolveAdapterType(DockerClient dockerClient, ComputeAdapterType configured) {
        if (configured == null || configured == ComputeAdapterType.AUTO) {
            // AUTO: detect Swarm manager; default to single-node Docker if this
            // engine is not a Swarm manager (workers cannot create services).
            boolean isManager = isSwarmManager(dockerClient);
            return isManager ? ComputeAdapterType.SWARM_STACK : ComputeAdapterType.DOCKER_SINGLE;
        }
        if (configured == ComputeAdapterType.SWARM_STACK) {
            if (!isSwarmManager(dockerClient)) {
                throw new IllegalStateException(
                    "Compute adapter configured as SWARM_STACK but this Docker engine is not a Swarm manager. "
                        + "Run orchestrator against a Swarm manager node or use DOCKER_SINGLE/AUTO.");
            }
        }
        return configured;
    }

    private boolean isSwarmManager(DockerClient dockerClient) {
        var info = dockerClient.infoCmd().exec();
        var swarm = info.getSwarm();
        if (swarm == null) {
            return false;
        }
        var state = swarm.getLocalNodeState();
        // Local node must be active AND have control available to be a manager.
        boolean active = state != null && state.name().equalsIgnoreCase("active");
        Boolean controlAvailable = swarm.getControlAvailable();
        return active && Boolean.TRUE.equals(controlAvailable);
    }
}
