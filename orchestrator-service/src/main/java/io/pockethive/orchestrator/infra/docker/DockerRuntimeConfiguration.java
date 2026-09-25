package io.pockethive.orchestrator.infra.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.docker.DockerEngine;
import io.pockethive.docker.DockerRuntimeClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: supply the Docker runtime operations API using application serialization.
 * Must not: construct a second Docker connection or decide cleanup behavior.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
@Configuration
public class DockerRuntimeConfiguration {
    @Bean
    public DockerRuntimeClient dockerRuntimeClient(DockerEngine engine, ObjectMapper objectMapper) {
        return engine.runtime(objectMapper);
    }
}
