package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitConnectionSettings;

class ControlPlaneContainerEnvironmentFactoryTest {

    @Test
    void controllerEnvironmentBuildsCompleteMap() {
        ControlPlaneProperties controlPlaneProperties = new ControlPlaneProperties();
        controlPlaneProperties.setExchange("ph.control");
        controlPlaneProperties.setControlQueuePrefix("ph.control");
        controlPlaneProperties.getWorker().setEnabled(false);
        controlPlaneProperties.setSwarmId("swarm-1");
        controlPlaneProperties.setInstanceId("controller-a");
        io.pockethive.controlplane.spring.MetricsSettings metrics =
            clickHouseMetrics(Duration.ofSeconds(15));
        io.pockethive.controlplane.spring.ControllerSettings settings =
            new io.pockethive.controlplane.spring.ControllerSettings(
                metrics,
                "run-1");
        RabbitConnectionSettings rabbitConnection = rabbitConnection();

        Map<String, String> env = ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
            "swarm-1",
            "controller-a",
            "swarm-controller",
            controlPlaneProperties,
            settings,
            rabbitConnection);

        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "controller-a");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_ID", "swarm-1");
        assertThat(env).containsEntry(
            "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX",
            "ph.control");
        assertThat(env).doesNotContainKeys(
            "POCKETHIVE_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_ADAPTER", "CLICKHOUSE");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUBLISH_INTERVAL", "PT15S");
        assertThat(env).containsEntry(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_ENDPOINT",
            "http://clickhouse:8123");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ADAPTER", "CLICKHOUSE");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_PUBLISH_INTERVAL", "PT15S");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", "http://clickhouse:8123");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_SWARM_ID", "swarm-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_RUN_ID", "run-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ROLE", "swarm-controller");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_INSTANCE", "controller-a");
        assertThat(env).containsEntry("SPRING_RABBITMQ_HOST", "rabbitmq");
    }

    @Test
    void workerEnvironmentBuildsMap() {
        io.pockethive.controlplane.spring.MetricsSettings metrics =
            clickHouseMetrics(Duration.ofSeconds(20));
        io.pockethive.controlplane.spring.WorkerSettings settings =
            new io.pockethive.controlplane.spring.WorkerSettings(
                "swarm-1",
                "run-1",
                "ph.control",
                "ph.control",
                metrics);
        RabbitConnectionSettings rabbitConnection = rabbitConnection();

        Map<String, String> env = ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            "bee-a",
            "processor",
            settings,
            rabbitConnection);

        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "bee-a");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_WORKER_ROLE", "processor");
        assertThat(env).doesNotContainKeys(
            "POCKETHIVE_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE",
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX", "ph.control");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ADAPTER", "CLICKHOUSE");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_PUBLISH_INTERVAL", "PT20S");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", "http://clickhouse:8123");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_SWARM_ID", "swarm-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_RUN_ID", "run-1");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_ROLE", "processor");
        assertThat(env).containsEntry("POCKETHIVE_METRICS_INSTANCE", "bee-a");
        assertThat(env.keySet()).noneMatch(key -> key.startsWith("MANAGEMENT_"));
    }

    @Test
    void clickHouseMetricsSettingsPropagateToControllerAndWorker() {
        io.pockethive.controlplane.spring.MetricsSettings metrics =
            clickHouseMetrics(Duration.ofSeconds(10));
        RabbitConnectionSettings rabbitConnection = rabbitConnection();
        ControlPlaneProperties controlPlaneProperties = new ControlPlaneProperties();
        controlPlaneProperties.setExchange("ph.control");
        controlPlaneProperties.setControlQueuePrefix("ph.control");

        Map<String, String> controllerEnv = ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
            "swarm-1",
            "controller-a",
            "swarm-controller",
            controlPlaneProperties,
            new io.pockethive.controlplane.spring.ControllerSettings(
                metrics,
                "run-1"),
            rabbitConnection);

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
            new io.pockethive.controlplane.spring.WorkerSettings(
                "swarm-1",
                "run-1",
                "ph.control",
                "ph.control",
                metrics),
            rabbitConnection);

        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_ADAPTER", "CLICKHOUSE");
        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", "http://clickhouse:8123");
        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_CLICKHOUSE_TABLE", "ph_metrics_samples");
        assertThat(workerEnv).containsEntry("POCKETHIVE_METRICS_RUN_ID", "run-1");
    }

    @Test
    void clickHouseAdapterRequiresConfiguredSettings() {
        assertThatThrownBy(() -> new io.pockethive.controlplane.spring.MetricsSettings(
            PocketHiveMetricsAdapter.CLICKHOUSE,
            Duration.ofSeconds(10),
            ClickHouseMetricsSinkProperties.disabled()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("endpoint/table");
    }

    @Test
    void participantEnvironmentRequiresOnlyControlSettings() {
        var control = new ControlPlaneProperties();
        control.setExchange("ph.control");
        control.setControlQueuePrefix("ph.control");
        var settings = new ControllerSettings(disabledMetrics(Duration.ofSeconds(30)), "run");
        var environment = ControlPlaneContainerEnvironmentFactory.controllerEnvironment(
            "swarm", "controller", "swarm-controller", control, settings, rabbitConnection());
        assertThat(environment).containsEntry("SPRING_RABBITMQ_HOST", "rabbitmq");
        assertThat(environment.keySet()).noneMatch(key -> key.startsWith("POCKETHIVE_RABBIT_WORK_")
            || key.startsWith("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_"));
    }

    private static RabbitConnectionSettings rabbitConnection() {
        RabbitConnectionSettings properties = new RabbitConnectionSettings("rabbitmq", 5672, "guest", "guest", "/");
        return properties;
    }

    private static io.pockethive.controlplane.spring.MetricsSettings disabledMetrics(Duration publishInterval) {
        return new io.pockethive.controlplane.spring.MetricsSettings(
            PocketHiveMetricsAdapter.DISABLED,
            publishInterval,
            ClickHouseMetricsSinkProperties.disabled());
    }

    private static io.pockethive.controlplane.spring.MetricsSettings clickHouseMetrics(Duration publishInterval) {
        return new io.pockethive.controlplane.spring.MetricsSettings(
            PocketHiveMetricsAdapter.CLICKHOUSE,
            publishInterval,
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
