package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Duration;

final class SwarmControllerTestProperties {

    private SwarmControllerTestProperties() {
    }

    static SwarmControllerProperties defaults() {
        return new SwarmControllerProperties(
            Topology.SWARM_ID,
            Topology.CONTROL_EXCHANGE,
            new SwarmControllerProperties.Manager("swarm-controller"),
            new SwarmControllerProperties.SwarmController(
                "ph.control",
                new SwarmControllerProperties.Traffic(
                    "ph." + Topology.SWARM_ID + ".hive",
                    "ph." + Topology.SWARM_ID),
                new SwarmControllerProperties.Rabbit("ph.logs"),
                new SwarmControllerProperties.Metrics(
                    new SwarmControllerProperties.Pushgateway(false, null, Duration.ofMinutes(1), "DELETE")),
                new SwarmControllerProperties.Docker(null, "/var/run/docker.sock")));
    }
}
