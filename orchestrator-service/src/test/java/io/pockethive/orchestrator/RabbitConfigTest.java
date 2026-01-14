package io.pockethive.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RabbitConfigTest {

    @Test
    void buildsQueueNamesFromConfiguration() {
        ControlPlaneProperties controlPlane = new ControlPlaneProperties();
        controlPlane.setExchange("ph.control");
        controlPlane.setControlQueuePrefix("ph.control.orchestrator");
        controlPlane.setSwarmId("swarm-alpha");
        controlPlane.setInstanceId("orch-1");
        controlPlane.getManager().setRole("orchestrator");

        OrchestratorProperties properties = new OrchestratorProperties(
            new OrchestratorProperties.Orchestrator(
                "ph.control.orchestrator",
                "ph.control.orchestrator-status",
                new OrchestratorProperties.Rabbit(
                    "ph.logs",
                    new OrchestratorProperties.Logging(Boolean.FALSE)),
                new OrchestratorProperties.Metrics(
                    new OrchestratorProperties.Pushgateway(
                        true,
                        "http://pushgateway:9091",
                        Duration.ofMinutes(1),
                        "DELETE",
                        "swarm-job",
                        new OrchestratorProperties.GroupingKey("controller-instance"))),
                new OrchestratorProperties.Docker("/var/run/docker.sock", null),
                new OrchestratorProperties.Images(null),
                new OrchestratorProperties.ScenarioManager(
                    "http://scenario-manager:8080",
                    new OrchestratorProperties.Http(Duration.ofSeconds(5), Duration.ofSeconds(30)))));

        OrchestratorControlPlaneConfig config = new OrchestratorControlPlaneConfig(controlPlane, properties);
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-alpha", null, "orchestrator", "orch-1");

        assertThat(config.managerControlQueueName(identity))
            .isEqualTo("ph.control.orchestrator.orch-1");
        assertThat(config.controllerStatusQueueName(identity))
            .isEqualTo("ph.control.orchestrator-status.orch-1");
    }
}
