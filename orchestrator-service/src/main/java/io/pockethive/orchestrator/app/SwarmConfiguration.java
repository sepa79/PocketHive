package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmStateStore;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwarmConfiguration {

    @Bean
    public SwarmStore swarmStore() {
        return new SwarmStore();
    }

    @Bean
    public SwarmStateStore swarmStateStore(SwarmStore store, ObjectMapper objectMapper) {
        return new SwarmStateStore(store, objectMapper);
    }

    @Bean
    public SwarmCreateTracker swarmCreateTracker() {
        return new SwarmCreateTracker();
    }

    @Bean
    public SwarmOperationCoordinator swarmOperationCoordinator() {
        return new SwarmOperationCoordinator();
    }
}
