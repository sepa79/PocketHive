package io.pockethive.scenarios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

@SpringBootTest(properties = "rabbitmq.logging.enabled=false")
class ScenarioManagerApplicationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("scenarios.dir", () -> tempDir.toString());
        registry.add("capabilities.dir", () -> tempDir.resolve("capabilities").toString());
        registry.add("pockethive.scenarios.runtime-root", () -> tempDir.resolve("runtime").toString());
        registry.add("rabbitmq.logging.enabled", () -> "false");
    }

    @Test
    void contextLoads() {
        // Ensures application starts successfully
    }
}
