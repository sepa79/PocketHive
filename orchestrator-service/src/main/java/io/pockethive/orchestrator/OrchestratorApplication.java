package io.pockethive.orchestrator;

import io.pockethive.controlplane.spring.RabbitConnectionConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@Import(RabbitConnectionConfiguration.class)
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = {
    "io.pockethive.orchestrator.config",
    "io.pockethive.sink.clickhouse"
})
public class OrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
