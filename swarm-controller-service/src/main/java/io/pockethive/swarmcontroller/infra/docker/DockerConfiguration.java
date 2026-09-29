package io.pockethive.swarmcontroller.infra.docker;

import io.pockethive.docker.DockerEngine;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.ports.ComputeHost;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: supply Controller compute ports from its configured Docker engine.
 * Must not: construct SDK clients or select an alternative compute mode.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
@Configuration
public class DockerConfiguration {
    @Bean(destroyMethod = "close")
    public DockerEngine dockerEngine(SwarmControllerProperties properties) {
        var docker = properties.getDocker();
        return DockerEngine.forController(docker.host(), docker.socketPath());
    }

    @Bean
    public ComputeHost computeHost(DockerEngine engine) {
        return engine.host();
    }

    @Bean
    public ComputeAdapter computeAdapter(DockerEngine engine, SwarmControllerProperties properties) {
        return engine.controllerAdapter(properties.getDocker() == null ? null : properties.getDocker().computeAdapter());
    }
}
