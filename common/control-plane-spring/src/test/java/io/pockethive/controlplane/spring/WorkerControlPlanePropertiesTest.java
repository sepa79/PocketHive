package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerControlPlanePropertiesTest {

    @Test
    void rejectsNullTrafficExchange() {
        assertThatThrownBy(() -> buildProperties(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("pockethive.control-plane.traffic-exchange must not be null or blank");
    }

    @Test
    void rejectsPlaceholderTrafficExchange() {
        String placeholder = "${POCKETHIVE_CONTROL_PLANE_TRAFFIC_EXCHANGE}";
        assertThatThrownBy(() -> buildProperties(placeholder))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "pockethive.control-plane.traffic-exchange must resolve to a concrete value, but was "
                    + placeholder);
    }

    @Test
    void controlPlaneMetadataDerivedFromConfiguredIdentity() {
        WorkerControlPlaneProperties properties = buildProperties("ph.swarm-alpha.hive");

        WorkerControlPlaneProperties.ControlPlane controlPlane = properties.getControlPlane();
        assertThat(controlPlane.getControlQueueName())
            .isEqualTo("ph.control.swarm-alpha.generator.worker-1");
        assertThat(controlPlane.getRoutes().configSignals())
            .contains("sig.config-update.swarm-alpha.generator.{instance}");
        assertThat(controlPlane.getRoutes().statusSignals())
            .contains("sig.status-request.swarm-alpha.generator.{instance}");
    }

    private static WorkerControlPlaneProperties buildProperties(String trafficExchange) {
        Map<String, String> queues = Map.of("generator", "ph.swarm-alpha.generator");
        WorkerControlPlaneProperties.Worker worker = new WorkerControlPlaneProperties.Worker(
            true,
            true,
            "generator",
            null,
            true,
            null);
        WorkerControlPlaneProperties.SwarmController.Rabbit.Logging logging =
            new WorkerControlPlaneProperties.SwarmController.Rabbit.Logging(false);
        WorkerControlPlaneProperties.SwarmController.Rabbit rabbit =
            new WorkerControlPlaneProperties.SwarmController.Rabbit("ph.logs", logging);
        WorkerControlPlaneProperties.SwarmController.Metrics.Pushgateway pushgateway =
            new WorkerControlPlaneProperties.SwarmController.Metrics.Pushgateway(
                false,
                "http://push:9091",
                Duration.ofMinutes(1),
                "DELETE");
        WorkerControlPlaneProperties.SwarmController swarmController =
            new WorkerControlPlaneProperties.SwarmController(
                rabbit,
                new WorkerControlPlaneProperties.SwarmController.Metrics(pushgateway));
        return new WorkerControlPlaneProperties(
            true,
            true,
            "ph.control",
            trafficExchange,
            "swarm-alpha",
            "worker-1",
            queues,
            worker,
            swarmController);
    }
}
