package io.pockethive.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;

class OrchestratorPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class)
        .withPropertyValues(
            "pockethive.control-plane.orchestrator.metrics.adapter=DISABLED",
            "pockethive.control-plane.orchestrator.metrics.publish-interval=PT10S",
            "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
            "pockethive.control-plane.orchestrator.images.repository-prefix=",
            "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
            "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
            "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S",
            "pockethive.control-plane.orchestrator.network-proxy-manager.url=http://network-proxy-manager:8080",
            "pockethive.control-plane.orchestrator.network-proxy-manager.http.connect-timeout=PT5S",
            "pockethive.control-plane.orchestrator.network-proxy-manager.http.read-timeout=PT30S");

    @ParameterizedTest
    @ValueSource(strings = {"control-queue-prefix", "status-queue-prefix"})
    void rejectsRemovedQueuePrefixProperties(String key) {
        contextRunner.withPropertyValues("pockethive.control-plane.orchestrator." + key + "=obsolete")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class);
            });
    }

    @Test
    void bindsOrchestratorTreeFromControlPlanePrefix() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.orchestrator.metrics.adapter=DISABLED",
                "pockethive.control-plane.orchestrator.metrics.publish-interval=PT10S",
                "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
                "pockethive.control-plane.orchestrator.images.repository-prefix=",
                "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
                "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.url=http://network-proxy-manager:8080",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.read-timeout=PT30S")
            .run(context -> {
                assertThat(context).hasNotFailed();
                OrchestratorProperties properties = context.getBean(OrchestratorProperties.class);
                assertThat(properties.getMetrics().getAdapter())
                    .isEqualTo(PocketHiveMetricsAdapter.DISABLED);
                assertThat(properties.getMetrics().getPublishInterval())
                    .isEqualTo(Duration.ofSeconds(10));
                assertThat(properties.getMetrics().getClickHouse().configured()).isFalse();
                assertThat(properties.getDocker().getSocketPath()).isEqualTo("/var/run/docker.sock");
                assertThat(properties.getScenarioManager().getUrl())
                    .isEqualTo("http://scenario-manager:8080");
                assertThat(properties.getScenarioManager().getHttp().getConnectTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(5));
                assertThat(properties.getScenarioManager().getHttp().getReadTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(30));
                assertThat(properties.getNetworkProxyManager().getUrl())
                    .isEqualTo("http://network-proxy-manager:8080");
                assertThat(properties.getNetworkProxyManager().getHttp().getConnectTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(5));
                assertThat(properties.getNetworkProxyManager().getHttp().getReadTimeout())
                    .isEqualTo(java.time.Duration.ofSeconds(30));
            });
    }

    @Test
    void bindsClickHouseMetricsFromNestedControlPlanePrefix() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.orchestrator.metrics.adapter=CLICKHOUSE",
                "pockethive.control-plane.orchestrator.metrics.publish-interval=PT10S",
                "pockethive.control-plane.orchestrator.metrics.clickhouse.endpoint=http://clickhouse:8123",
                "pockethive.control-plane.orchestrator.docker.socket-path=/var/run/docker.sock",
                "pockethive.control-plane.orchestrator.images.repository-prefix=",
                "pockethive.control-plane.orchestrator.scenario-manager.url=http://scenario-manager:8080",
                "pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout=PT30S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.url=http://network-proxy-manager:8080",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.connect-timeout=PT5S",
                "pockethive.control-plane.orchestrator.network-proxy-manager.http.read-timeout=PT30S")
            .run(context -> {
                assertThat(context).hasNotFailed();
                ClickHouseMetricsSinkProperties clickHouse =
                    context.getBean(OrchestratorProperties.class).getMetrics().getClickHouse();
                assertThat(clickHouse.configured()).isTrue();
                assertThat(clickHouse.getEndpoint()).isEqualTo("http://clickhouse:8123");
                assertThat(clickHouse.getTable()).isEqualTo(ClickHouseMetricsSinkProperties.DEFAULT_TABLE);
                assertThat(clickHouse.getMaxBufferedSamples()).isEqualTo(50_000);
            });
    }

    @EnableConfigurationProperties(OrchestratorProperties.class)
    static class TestConfiguration {
        // registers OrchestratorProperties for ApplicationContextRunner
    }
}
