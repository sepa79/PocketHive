package io.pockethive.orchestrator.infra.docker;

import io.pockethive.docker.DockerEngine;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.ports.ComputeHost;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: supply Orchestrator compute ports from its application-owned Docker engine.
 * Must not: construct SDK clients or implement compute detection.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
@Configuration
public class DockerConfiguration {
    @Bean(destroyMethod = "close")
    public DockerEngine dockerEngine() {
        return DockerEngine.fromEnvironment();
    }

    @Bean
    public ComputeHost computeHost(DockerEngine engine) {
        return engine.host();
    }

    @Bean
    public ComputeAdapter computeAdapter(DockerEngine engine, OrchestratorProperties properties) {
        return engine.orchestratorAdapter(properties.getDocker() == null
            ? ComputeAdapterType.AUTO : properties.getDocker().getComputeAdapter());
    }
}
