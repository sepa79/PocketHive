package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
        ControlPlaneContainerEnvironmentFactory.ControllerSettings settings =
            new ControlPlaneContainerEnvironmentFactory.ControllerSettings(
                "ph.logs",
                true,
                true,
                "http://push:9091",
                Duration.ofSeconds(30),
                "DELETE",
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
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_CONTROL_QUEUE_PREFIX",
            "ph.control.swarm-1");
        assertThat(env).containsEntry("POCKETHIVE_LOGS_EXCHANGE", "ph.logs");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED", "true");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_BASE_URL", "http://push:9091");
        assertThat(env).containsEntry("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", "http://push:9091");
        assertThat(env).containsEntry("SPRING_RABBITMQ_HOST", "rabbitmq");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH", "/var/run/docker.sock");
    }

    @Test
    void workerEnvironmentBuildsMap() {
        ControlPlaneContainerEnvironmentFactory.WorkerSettings settings =
            new ControlPlaneContainerEnvironmentFactory.WorkerSettings(
                "swarm-1",
                "ph.control",
                "ph.swarm-1",
                "ph.swarm-1.hive",
                "ph.logs",
                true,
                true,
                "http://push:9091",
                Duration.ofSeconds(10),
                "POST");
        RabbitProperties rabbitProperties = rabbitProperties();

        Map<String, String> env = ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            "bee-a",
            settings,
            rabbitProperties);

        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", "bee-a");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE", "ph.logs");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR", "ph.swarm-1.gen");
        assertThat(env).containsEntry("POCKETHIVE_CONTROL_PLANE_TRAFFIC_EXCHANGE", "ph.swarm-1.hive");
        assertThat(env).containsEntry("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", "http://push:9091");
    }

    @Test
    void workerEnvironmentFailsForBlankRabbitHost() {
        ControlPlaneContainerEnvironmentFactory.WorkerSettings settings =
            new ControlPlaneContainerEnvironmentFactory.WorkerSettings(
                "swarm-1",
                "ph.control",
                "ph.swarm-1",
                "ph.swarm-1.hive",
                "ph.logs",
                false,
                false,
                null,
                Duration.ofMinutes(1),
                "DELETE");
        RabbitProperties rabbitProperties = new RabbitProperties();
        rabbitProperties.setHost("");

        assertThatThrownBy(() -> ControlPlaneContainerEnvironmentFactory.workerEnvironment(
            "bee-a",
            settings,
            rabbitProperties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.rabbitmq.host");
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
}
