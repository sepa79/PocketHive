package io.pockethive.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrchestratorPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsOrchestratorTreeFromControlPlanePrefix() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.orchestrator.control-queue-prefix=ph.control.orchestrator",
                "pockethive.control-plane.orchestrator.status-queue-prefix=ph.control.orchestrator-status",
                "pockethive.control-plane.orchestrator.rabbit.logs-exchange=ph.logs",
                "pockethive.control-plane.orchestrator.rabbit.logging.enabled=true",
                "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
                "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
                "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S")
            .run(context -> {
                assertThat(context).hasNotFailed();
                OrchestratorProperties properties = context.getBean(OrchestratorProperties.class);
                assertThat(properties.getControlQueuePrefix()).isEqualTo("ph.control.orchestrator");
                assertThat(properties.getStatusQueuePrefix()).isEqualTo("ph.control.orchestrator-status");
                assertThat(properties.getRabbit().getLogsExchange()).isEqualTo("ph.logs");
                assertThat(properties.getRabbit().getLogging().isEnabled()).isTrue();
                assertThat(properties.getDocker().getSocketPath()).isEqualTo("/var/run/docker.sock");
                assertThat(properties.getScenarioManager().getUrl())
                    .isEqualTo("http://scenario-manager:8080");
                assertThat(properties.getScenarioManager().getHttp().getConnectTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(5));
                assertThat(properties.getScenarioManager().getHttp().getReadTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(30));
            });
    }

    @Test
    void failsWhenRequiredControlQueuePrefixMissing() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.orchestrator.status-queue-prefix=ph.control.orchestrator-status",
                "pockethive.control-plane.orchestrator.rabbit.logs-exchange=ph.logs",
                "pockethive.control-plane.orchestrator.rabbit.logging.enabled=false",
                "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
                "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
                "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S")
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                assertThat(failure)
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
                Throwable root = failure;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root.getMessage()).contains("controlQueuePrefix");
            });
    }

    @EnableConfigurationProperties(OrchestratorProperties.class)
    static class TestConfiguration {
        // registers OrchestratorProperties for ApplicationContextRunner
    }
}
