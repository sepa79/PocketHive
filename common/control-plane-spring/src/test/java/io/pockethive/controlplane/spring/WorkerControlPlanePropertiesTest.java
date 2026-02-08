package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerControlPlanePropertiesTest {

    @Test
    void controlPlaneMetadataDerivedFromConfiguredIdentity() {
        WorkerControlPlaneProperties properties = buildProperties();

        WorkerControlPlaneProperties.ControlPlane controlPlane = properties.getControlPlane();
        assertThat(controlPlane.getControlQueueName())
            .isEqualTo("ph.control.swarm-alpha.generator.worker-1");
        assertThat(controlPlane.getRoutes().configSignals())
            .contains("signal.config-update.swarm-alpha.generator.{instance}");
        assertThat(controlPlane.getRoutes().statusSignals())
            .contains("signal.status-request.swarm-alpha.generator.{instance}");
    }

    private static WorkerControlPlaneProperties buildProperties() {
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
        WorkerControlPlaneProperties.SwarmController swarmController =
            new WorkerControlPlaneProperties.SwarmController(rabbit);
        return new WorkerControlPlaneProperties(
            true,
            true,
            "ph.control",
            "swarm-alpha",
            "worker-1",
            "ph.control",
            worker,
            swarmController);
    }
}
