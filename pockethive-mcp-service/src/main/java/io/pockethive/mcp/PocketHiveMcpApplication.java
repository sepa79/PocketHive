package io.pockethive.mcp;

import io.pockethive.mcp.config.EnvironmentHealthProperties;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({PocketHiveMcpProperties.class, EnvironmentHealthProperties.class})
public class PocketHiveMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(PocketHiveMcpApplication.class, args);
    }
}
