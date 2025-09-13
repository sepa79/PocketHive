package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwarmConfiguration {
    @Bean
    public SwarmRegistry swarmRegistry() {
        return new SwarmRegistry();
    }

    @Bean
    public SwarmPlanRegistry swarmPlanRegistry() {
        return new SwarmPlanRegistry();
    }

    @Bean
    public SwarmCreateTracker swarmCreateTracker() {
        return new SwarmCreateTracker();
    }
}

