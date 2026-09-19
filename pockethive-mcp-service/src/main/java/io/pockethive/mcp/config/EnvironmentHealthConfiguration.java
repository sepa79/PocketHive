package io.pockethive.mcp.config;

import io.pockethive.mcp.application.EnvironmentHealthProbePort;
import io.pockethive.mcp.application.EnvironmentHealthService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: Assemble environment-health application services from validated probe configuration.
 * Must not: Own domain transitions or reconstruct configuration outside the canonical properties.
 * Contract: docs/mcp/README.md.
 */

@Configuration
public class EnvironmentHealthConfiguration {
    @Bean
    EnvironmentHealthService environmentHealthService(PocketHiveMcpProperties properties,
                                                       EnvironmentHealthProperties healthProperties,
                                                       EnvironmentHealthProbePort probes,
                                                       Clock clock) {
        return new EnvironmentHealthService(properties.pocketHiveIngress(), healthProperties.catalogue(),
            probes, clock);
    }
}
