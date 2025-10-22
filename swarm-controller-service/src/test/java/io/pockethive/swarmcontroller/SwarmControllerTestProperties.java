package io.pockethive.swarmcontroller;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;

final class SwarmControllerTestProperties {

    static final String TEST_SWARM_ID = "default";
    static final String CONTROL_EXCHANGE = "ph.control";
    static final String CONTROL_QUEUE_PREFIX_BASE = "ph.control";
    static final String CONTROL_QUEUE_PREFIX = CONTROL_QUEUE_PREFIX_BASE + "." + TEST_SWARM_ID;
    static final String TRAFFIC_PREFIX = "ph." + TEST_SWARM_ID;
    static final String HIVE_EXCHANGE = TRAFFIC_PREFIX + ".hive";
    static final String LOGS_EXCHANGE = "ph.logs";

    private SwarmControllerTestProperties() {
    }

    static SwarmControllerProperties defaults() {
        return new SwarmControllerProperties(
            TEST_SWARM_ID,
            CONTROL_EXCHANGE,
            CONTROL_QUEUE_PREFIX_BASE,
            new SwarmControllerProperties.Manager("swarm-controller"),
            new SwarmControllerProperties.SwarmController(
                new SwarmControllerProperties.Traffic(HIVE_EXCHANGE, TRAFFIC_PREFIX),
                new SwarmControllerProperties.Rabbit(
                    LOGS_EXCHANGE,
                    new SwarmControllerProperties.Logging(true)),
                new SwarmControllerProperties.Docker(null, "/var/run/docker.sock")));
    }
}
