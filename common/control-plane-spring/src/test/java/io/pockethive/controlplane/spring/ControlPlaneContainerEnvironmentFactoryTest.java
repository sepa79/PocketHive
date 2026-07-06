package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

class ControlPlaneContainerEnvironmentFactoryTest {

    @Test
    void controllerEnvironmentBuildsCompleteMap() {
        ControlPlaneProperties controlPlaneProperties = new ControlPlaneProperties();
        controlPlaneProperties.setExchange("ph.control");
        controlPlaneProperties.setControlQueuePrefix("ph.control");
        controlPlaneProperties.getWorker().setEnabled(false);
        controlPlaneProperties.setSwarmId("swarm-1");
        controlPlaneProperties.setInstanceId("controller-a");
        ControlPlaneContainerEnvironmentFactory.MetricsSettings metrics =
            prometheusMetrics(Duration.ofSeconds(30), Duration.ofSeconds(15));
        ControlPlaneContainerEnvironmentFactory.ControllerSettings settings =
            new ControlPlaneContainerEnvironmentFactory.ControllerSettings(
                metrics,
                "run-1",
                "/var/run/docker.sock",
                "ph.swarm-1",
                "ph.swarm-1.hive");
        RabbitProperties rabbitProperties = rabbitProperties();

        Map<String, String> env = ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
            "swarm-1",
            "controller-a",
            "swarm-controller",
            controlPlaneProperties,
            settings,
            rabbitProperties);

        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "controller-a");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_ID", "swarm-1");
        assertThat(env).containsEntry(
            "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX",
            "ph.control");
        assertThat(env).doesNotContainKeys(
            "POCKETHIVE_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED", "true");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_BASE_URL", "http://pushgateway:9091");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE", "PT30S");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION", "DELETE");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_ADAPTER", "PROMETHEUS_PUSHGATEWAY");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUBLISH_INTERVAL", "PT15S");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ADAPTER", "PROMETHEUS_PUSHGATEWAY");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_PUBLISH_INTERVAL", "PT15S");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_SWARM_ID", "swarm-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_RUN_ID", "run-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ROLE", "swarm-controller");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_INSTANCE", "controller-a");
        assertThat(env).containsEntry("SPRING_RABBITMQ_HOST", "rabbitmq");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", "/var/run/docker.sock");
    }

    @Test
    void workerEnvironmentBuildsMap() {
        ControlPlaneContainerEnvironmentFactory.MetricsSettings metrics =
            prometheusMetrics(Duration.ofSeconds(45), Duration.ofSeconds(20));
        ControlPlaneContainerEnvironmentFactory.WorkerSettings settings =
            new ControlPlaneContainerEnvironmentFactory.WorkerSettings(
                "swarm-1",
                "run-1",
                "ph.control",
                "ph.control",
                "ph.swarm-1.hive",
                metrics);
        RabbitProperties rabbitProperties = rabbitProperties();

        Map<String, String> env = ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            "bee-a",
            "processor",
            settings,
            rabbitProperties);

        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "bee-a");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_WORKER_ROLE", "processor");
        assertThat(env).doesNotContainKeys(
            "POCKETHIVE_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX", "ph.control");
        assertThat(env).containsEntry("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED", "true");
        assertThat(env).containsEntry("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", "http://pushgateway:9091");
        assertThat(env).containsEntry("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE", "PT45S");
        assertThat(env).containsEntry("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION", "DELETE");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ADAPTER", "PROMETHEUS_PUSHGATEWAY");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_PUBLISH_INTERVAL", "PT20S");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_SWARM_ID", "swarm-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_RUN_ID", "run-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ROLE", "processor");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_INSTANCE", "bee-a");
    }

    @Test
    void clickHouseMetricsSettingsPropagateToControllerAndWorker() {
        ControlPlaneContainerEnvironmentFactory.MetricsSettings metrics =
            clickHouseMetrics(Duration.ofSeconds(10));
        RabbitProperties rabbitProperties = rabbitProperties();
        ControlPlaneProperties controlPlaneProperties = new ControlPlaneProperties();
        controlPlaneProperties.setExchange("ph.control");
        controlPlaneProperties.setControlQueuePrefix("ph.control");

        Map<String, String> controllerEnv = ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
            "swarm-1",
            "controller-a",
            "swarm-controller",
            controlPlaneProperties,
            new ControlPlaneContainerEnvironmentFactory.ControllerSettings(
                metrics,
                "run-1",
                "/var/run/docker.sock",
                "ph.swarm-1",
                "ph.swarm-1.hive"),
            rabbitProperties);

        assertThat(controllerEnv).containsEntry("POCKETHIVE_METRICS_ADAPTER", "CLICKHOUSE");
        assertThat(controllerEnv).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", "http://clickhouse:8123");
        assertThat(controllerEnv).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_TABLE", "ph_metrics_samples");
        assertThat(controllerEnv).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_MAX_BUFFERED_SAMPLES", "1234");
        assertThat(controllerEnv).containsEntry(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_ENDPOINT",
            "http://clickhouse:8123");

        Map<String, String> workerEnv = ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            "bee-a",
            "processor",
            new ControlPlaneContainerEnvironmentFactory.WorkerSettings(
                "swarm-1",
                "run-1",
                "ph.control",
                "ph.control",
                "ph.swarm-1.hive",
                metrics),
            rabbitProperties);

        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_ADAPTER", "CLICKHOUSE");
        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", "http://clickhouse:8123");
        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_TABLE", "ph_metrics_samples");
        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_RUN_ID", "run-1");
    }

    @Test
    void nonPrometheusMetricsAdapterRejectsEnabledPushgateway() {
        assertThatThrownBy(() -> new ControlPlaneContainerEnvironmentFactory.MetricsSettings(
            PocketHiveMetricsAdapter.CLICKHOUSE,
            Duration.ofSeconds(10),
            new ControlPlaneContainerEnvironmentFactory.PushgatewaySettings(
                true,
                "http://pushgateway:9091",
                Duration.ofSeconds(30),
                "DELETE"),
            clickHouseProperties(
                "http://clickhouse:8123",
                "ph_metrics_samples",
                "",
                "",
                1000,
                2000,
                100,
                50,
                1234,
                12,
                40,
                120)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pushgateway.enabled");
    }

    @Test
    void workerEnvironmentFailsForBlankRabbitHost() {
        ControlPlaneContainerEnvironmentFactory.WorkerSettings settings =
            new ControlPlaneContainerEnvironmentFactory.WorkerSettings(
                "swarm-1",
                "run-1",
                "ph.control",
                "ph.control",
                "ph.swarm-1.hive",
                prometheusMetrics(Duration.ofSeconds(30), Duration.ofSeconds(30)));
        RabbitProperties rabbitProperties = new RabbitProperties();
        rabbitProperties.setHost("");

        assertThatThrownBy(() -> ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            "bee-a",
            "processor",
            settings,
            rabbitProperties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.rabbitmq.host");
    }

    @Test
    void buildsSwarmTrafficQueueNamesFromControllerEnvironmentContract() {
        ControlPlaneContainerEnvironmentFactory.ControllerSettings settings =
            new ControlPlaneContainerEnvironmentFactory.ControllerSettings(
                prometheusMetrics(Duration.ofSeconds(30), Duration.ofSeconds(30)),
                "run-1",
                "/var/run/docker.sock",
                "ph.swarm-1",
                "ph.swarm-1.hive");

        assertThat(settings.trafficQueueName("gen")).isEqualTo("ph.swarm-1.gen");
        assertThat(settings.trafficQueueNames(List.of("gen", "final", "gen")))
            .containsExactly("ph.swarm-1.gen", "ph.swarm-1.final");
        assertThatThrownBy(() -> settings.trafficQueueName(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("traffic queue suffix");
    }

    private static RabbitProperties rabbitProperties() {
        RabbitProperties properties = new RabbitProperties();
        properties.setHost("rabbitmq");
        properties.setPort(5672);
        properties.setUsername("guest");
        properties.setPassword("guest");
        properties.setVirtualHost("/");
        return properties;
    }

    private static ControlPlaneContainerEnvironmentFactory.MetricsSettings prometheusMetrics(
        Duration pushRate,
        Duration publishInterval) {
        return new ControlPlaneContainerEnvironmentFactory.MetricsSettings(
            PocketHiveMetricsAdapter.PROMETHEUS_PUSHGATEWAY,
            publishInterval,
            new ControlPlaneContainerEnvironmentFactory.PushgatewaySettings(
                true,
                "http://pushgateway:9091",
                pushRate,
                "DELETE"),
            ClickHouseMetricsSinkProperties.disabled());
    }

    private static ControlPlaneContainerEnvironmentFactory.MetricsSettings clickHouseMetrics(Duration publishInterval) {
        return new ControlPlaneContainerEnvironmentFactory.MetricsSettings(
            PocketHiveMetricsAdapter.CLICKHOUSE,
            publishInterval,
            new ControlPlaneContainerEnvironmentFactory.PushgatewaySettings(
                false,
                "http://pushgateway:9091",
                Duration.ofSeconds(30),
                "DELETE"),
            clickHouseProperties(
                "http://clickhouse:8123",
                "ph_metrics_samples",
                "pockethive",
                "pockethive",
                1000,
                2000,
                100,
                50,
                1234,
                12,
                40,
                120));
    }

    private static ClickHouseMetricsSinkProperties clickHouseProperties(String endpoint,
                                                                        String table,
                                                                        String username,
                                                                        String password,
                                                                        int connectTimeoutMs,
                                                                        int readTimeoutMs,
                                                                        int batchSize,
                                                                        int flushIntervalMs,
                                                                        int maxBufferedSamples,
                                                                        int maxLabelCount,
                                                                        int maxLabelKeyLength,
                                                                        int maxLabelValueLength) {
        ClickHouseMetricsSinkProperties properties = new ClickHouseMetricsSinkProperties();
        properties.setEndpoint(endpoint);
        properties.setTable(table);
        properties.setUsername(username);
        properties.setPassword(password);
        properties.setConnectTimeoutMs(connectTimeoutMs);
        properties.setReadTimeoutMs(readTimeoutMs);
        properties.setBatchSize(batchSize);
        properties.setFlushIntervalMs(flushIntervalMs);
        properties.setMaxBufferedSamples(maxBufferedSamples);
        properties.setMaxLabelCount(maxLabelCount);
        properties.setMaxLabelKeyLength(maxLabelKeyLength);
        properties.setMaxLabelValueLength(maxLabelValueLength);
        return properties;
    }
}
