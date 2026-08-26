package io.pockethive.mcp;

import io.pockethive.mcp.config.EnvironmentHealthProperties;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Responsibility: Bootstrap the PocketHive MCP application.
 * Must not: Own domain, transport, or persistence behavior.
 * Contract: docs/mcp/README.md.
 */

@SpringBootApplication
@EnableConfigurationProperties({PocketHiveMcpProperties.class, EnvironmentHealthProperties.class})
public class PocketHiveMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocketHiveMcpApplication.class, args);
    }
}
