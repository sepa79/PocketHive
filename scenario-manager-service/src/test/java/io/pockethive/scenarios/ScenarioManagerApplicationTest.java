package io.pockethive.scenarios;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

@SpringBootTest(properties = "RABBITMQ_LOGGING_ENABLED=false")
class ScenarioManagerApplicationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("scenarios.dir", () -> tempDir.toString());
        registry.add("RABBITMQ_LOGGING_ENABLED", () -> "false");
    }

    @Test
    void contextLoads() {
        // Ensures application starts successfully
    }
}
