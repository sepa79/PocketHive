package io.pockethive.controlplane.spring;

import io.pockethive.observability.metrics.PocketHiveMetricsAdapter;
import io.pockethive.sink.clickhouse.metrics.ClickHouseMetricsSinkProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.pockethive.rabbit.api.RabbitConnectionSettings;
import io.pockethive.rabbit.api.RabbitConnectionEnvironment;

/**
 * Builds environment maps for control-plane participants so services share a consistent
 * contract when the orchestrator launches controller and worker containers.
 * Responsibility: compose participant environment values with the canonical connection export.
 * Must not: validate or encode Rabbit connection fields independently.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 * Work environment is composed separately through its selected owner.
 */
public final class ControlPlaneContainerEnvironmentFactory {


    private ControlPlaneContainerEnvironmentFactory() {
    }

    public static Map<String, String> controllerEnvironment(String swarmId,
                                                            String instanceId,
                                                            String managerRole,
                                                            ControlPlaneProperties controlPlaneProperties,
                                                            ControllerSettings settings,
                                                            RabbitConnectionSettings rabbitConnection) {
        String resolvedSwarmId = requireArgument(swarmId, "swarmId");
        String resolvedInstance = requireArgument(instanceId, "controller instance");
        Objects.requireNonNull(settings, "settings");

        Map<String, String> env = new LinkedHashMap<>();
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", resolvedInstance);
        env.put(
            "POCKETHIVE_CONTROL_PLANE_EXCHANGE",
            requireSetting(controlPlaneProperties.getExchange(), "pockethive.control-plane.exchange"));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", resolvedSwarmId);
        env.putAll(RabbitConnectionEnvironment.encode(rabbitConnection));
        env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED",
            Boolean.toString(controlPlaneProperties.getWorker().isEnabled()));
        env.put("POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE", requireSetting(managerRole, "pockethive.control-plane.manager.role"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX",
            requireSetting(controlPlaneProperties.getControlQueuePrefix(),
                "pockethive.control-plane.control-queue-prefix"));
        applyPocketHiveMetricsSettings(
            env,
            settings.metrics(),
            resolvedSwarmId,
            settings.runId(),
            managerRole,
            resolvedInstance);
        applyControlPlaneMetricsSettings(env, settings.metrics());
        return env;
    }

    public static Map<String, String> workerEnvironment(String instanceId,
                                                        String role,
                                                        WorkerSettings settings,
                                                        RabbitConnectionSettings rabbitConnection) {
        String resolvedInstance = requireArgument(instanceId, "worker instance");
        String resolvedRole = requireArgument(role, "worker role");
        Objects.requireNonNull(settings, "settings");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", resolvedInstance);
        env.put("POCKETHIVE_CONTROL_PLANE_WORKER_ROLE", resolvedRole);
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", requireSetting(settings.swarmId(), "pockethive.control-plane.swarm-id"));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_EXCHANGE",
            requireSetting(settings.controlExchange(), "pockethive.control-plane.exchange"));
        env.putAll(RabbitConnectionEnvironment.encode(rabbitConnection));
        env.put(
            "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX",
            requireSetting(settings.controlQueuePrefix(), "pockethive.control-plane.control-queue-prefix"));
        applyPocketHiveMetricsSettings(
            env,
            settings.metrics(),
            settings.swarmId(),
            settings.runId(),
            resolvedRole,
            resolvedInstance);
        return env;
    }

    private static String requireSetting(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be null or blank");
        }
        return value;
    }

    private static void applyControlPlaneMetricsSettings(
        Map<String, String> env,
        MetricsSettings metrics) {
        Objects.requireNonNull(metrics, "metrics");
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_ADAPTER",
            metrics.adapter().name());
        env.put(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUBLISH_INTERVAL",
            metrics.publishInterval().toString());
        applyClickHouseControlPlaneSettings(env, metrics.clickHouse());
    }

    private static void applyPocketHiveMetricsSettings(Map<String, String> env,
                                                       MetricsSettings metrics,
                                                       String swarmId,
                                                       String runId,
                                                       String role,
                                                       String instance) {
        Objects.requireNonNull(metrics, "metrics");
        env.put("POCKETHIVE_METRICS_ADAPTER", metrics.adapter().name());
        env.put("POCKETHIVE_METRICS_PUBLISH_INTERVAL", metrics.publishInterval().toString());
        env.put("POCKETHIVE_METRICS_SWARM_ID", requireSetting(swarmId, "pockethive.metrics.swarm-id"));
        env.put("POCKETHIVE_METRICS_RUN_ID", requireSetting(runId, "pockethive.metrics.run-id"));
        env.put("POCKETHIVE_METRICS_ROLE", requireSetting(role, "pockethive.metrics.role"));
        env.put("POCKETHIVE_METRICS_INSTANCE", requireSetting(instance, "pockethive.metrics.instance"));
        applyClickHouseMetricsExport(env, metrics.clickHouse());
    }

    private static void applyClickHouseControlPlaneSettings(Map<String, String> env,
                                                            ClickHouseMetricsSinkProperties clickHouse) {
        if (!clickHouse.configured()) {
            return;
        }
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_ENDPOINT", clickHouse.getEndpoint());
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_TABLE", clickHouse.getTable());
        putIfNotBlank(env, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_USERNAME",
            clickHouse.getUsername());
        putIfNotBlank(env, "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_PASSWORD",
            clickHouse.getPassword());
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_CONNECT_TIMEOUT_MS",
            Integer.toString(clickHouse.getConnectTimeoutMs()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_READ_TIMEOUT_MS",
            Integer.toString(clickHouse.getReadTimeoutMs()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_BATCH_SIZE",
            Integer.toString(clickHouse.getBatchSize()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_FLUSH_INTERVAL_MS",
            Integer.toString(clickHouse.getFlushIntervalMs()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_BUFFERED_SAMPLES",
            Integer.toString(clickHouse.getMaxBufferedSamples()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_COUNT",
            Integer.toString(clickHouse.getMaxLabelCount()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_KEY_LENGTH",
            Integer.toString(clickHouse.getMaxLabelKeyLength()));
        env.put("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_CLICKHOUSE_MAX_LABEL_VALUE_LENGTH",
            Integer.toString(clickHouse.getMaxLabelValueLength()));
    }

    private static void applyClickHouseMetricsExport(Map<String, String> env,
                                                     ClickHouseMetricsSinkProperties clickHouse) {
        if (!clickHouse.configured()) {
            return;
        }
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT", clickHouse.getEndpoint());
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_TABLE", clickHouse.getTable());
        putIfNotBlank(env, "POCKETHIVE_METRICS_CLICKHOUSE_USERNAME", clickHouse.getUsername());
        putIfNotBlank(env, "POCKETHIVE_METRICS_CLICKHOUSE_PASSWORD", clickHouse.getPassword());
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_CONNECT_TIMEOUT_MS", Integer.toString(clickHouse.getConnectTimeoutMs()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_READ_TIMEOUT_MS", Integer.toString(clickHouse.getReadTimeoutMs()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_BATCH_SIZE", Integer.toString(clickHouse.getBatchSize()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_FLUSH_INTERVAL_MS", Integer.toString(clickHouse.getFlushIntervalMs()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_BUFFERED_SAMPLES",
            Integer.toString(clickHouse.getMaxBufferedSamples()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_LABEL_COUNT", Integer.toString(clickHouse.getMaxLabelCount()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_LABEL_KEY_LENGTH",
            Integer.toString(clickHouse.getMaxLabelKeyLength()));
        env.put("POCKETHIVE_METRICS_CLICKHOUSE_MAX_LABEL_VALUE_LENGTH",
            Integer.toString(clickHouse.getMaxLabelValueLength()));
    }

    private static void putIfNotBlank(Map<String, String> env, String key, String value) {
        if (value != null && !value.isBlank()) {
            env.put(key, value);
        }
    }

    static String requireArgument(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be null or blank");
        }
        return value;
    }

}
