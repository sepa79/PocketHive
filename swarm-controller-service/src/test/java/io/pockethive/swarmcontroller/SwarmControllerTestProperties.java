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
            "swarm-controller",
            Topology.CONTROL_EXCHANGE,
            "ph.control",
            new SwarmControllerProperties.Traffic(null, null),
            new SwarmControllerProperties.Rabbit("rabbitmq", "ph.logs"),
            new SwarmControllerProperties.Metrics(
                new SwarmControllerProperties.Pushgateway(false, null, Duration.ofMinutes(1), "DELETE")),
            new SwarmControllerProperties.Docker(null, "/var/run/docker.sock"));
    }
}
