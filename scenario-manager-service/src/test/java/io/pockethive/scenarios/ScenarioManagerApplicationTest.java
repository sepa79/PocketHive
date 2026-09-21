package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.rabbit.api.RabbitConnections;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

@SpringBootTest
class ScenarioManagerApplicationTest {

    @Autowired
    ApplicationContext context;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("scenarios.dir", () -> tempDir.toString());
        registry.add("capabilities.dir", () -> tempDir.resolve("capabilities").toString());
    }

    @Test
    void authoringStartsWithoutRabbitRuntime() {
        assertThat(context.getBeansOfType(RabbitConnections.class)).isEmpty();
        assertThat(context.getBeansOfType(RabbitPublisher.class)).isEmpty();
    }
}
