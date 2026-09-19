package io.pockethive.mcp.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: Provide the canonical application clock dependency.
 * Must not: Own domain transitions or reconstruct configuration outside the canonical properties.
 * Contract: docs/mcp/README.md.
 */

@Configuration
public class TimeConfiguration {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
