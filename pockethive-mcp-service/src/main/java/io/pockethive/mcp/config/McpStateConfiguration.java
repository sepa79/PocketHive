package io.pockethive.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.adapter.persistence.AtomicCoordinationStateRepository;
import io.pockethive.mcp.application.CoordinationStateRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpStateConfiguration {
    @Bean(destroyMethod = "close")
    CoordinationStateRepository coordinationStateRepository(ObjectMapper mapper,
                                                              PocketHiveMcpProperties properties) {
        return new AtomicCoordinationStateRepository(mapper, properties.stateMode(), properties.statePath(),
            properties.maxStateBytes(), properties.maxOpenSessions(),
            properties.maxOpenSessionsPerPrincipal());
    }
}
