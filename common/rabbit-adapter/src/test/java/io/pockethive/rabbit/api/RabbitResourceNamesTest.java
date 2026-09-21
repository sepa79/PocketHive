package io.pockethive.rabbit.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RabbitResourceNamesTest {
    private final RabbitResourceNames names = new RabbitResourceNames();

    @Test
    void resolvesExplicitNamesWithoutInferringAlreadyPrefixedAliases() {
        assertEquals("ph.swarm.jobs", names.queueName(" ph.swarm ", " jobs "));
        assertEquals("ph.swarm.ph.swarm.jobs", names.queueName("ph.swarm", "ph.swarm.jobs"));
        assertEquals("ph.swarm.hive", names.exchangeName(" ph.swarm.hive "));
    }

    @Test
    void resolvesWorkAddressWithExistingRoutingAndNormalization() {
        var address = names.address(" hive ", " ph.swarm ", " stage.in ");
        assertEquals("hive", address.exchange());
        assertEquals("ph.swarm.stage.in", address.queue());
        assertEquals("ph.swarm.stage.in", address.routingKey());
    }

    @Test
    void derivesSwarmSettingsThroughTheSameOwner() {
        var topology = names.forSwarm(" swarm ");
        assertEquals("ph.swarm", topology.queuePrefix());
        assertEquals("ph.swarm.hive", topology.hiveExchange());
        assertEquals("ph.swarm.jobs", names.queueName(topology.queuePrefix(), "jobs"));
        assertThrows(IllegalArgumentException.class, () -> names.forSwarm(" "));
    }

    @Test
    void rejectsMissingTopologySettings() {
        assertThrows(IllegalArgumentException.class, () -> names.queueName(null, "jobs"));
        assertThrows(IllegalArgumentException.class, () -> names.queueName("prefix", " "));
        assertThrows(IllegalArgumentException.class, () -> names.exchangeName(""));
    }
    @Test
    void preservesControlQueueAndDebugTapNames() {
        var rabbit = new RabbitResourceNames();
        assertEquals("ph.control.alpha.processor.worker-1", rabbit.workerControlQueue("ph.control", "alpha", "processor", "worker-1"));
        assertEquals("ph.control.alpha.swarm-controller.manager-1", rabbit.swarmControllerQueue("ph.control", "alpha", "swarm-controller", "manager-1"));
        assertEquals("ph.control.alpha.swarm-controller.manager-1", rabbit.swarmControllerQueue("ph.control.alpha", "alpha", "swarm-controller", "manager-1"));
        assertEquals("ph.control.orchestrator.main", rabbit.managerControlQueue("ph.control", "orchestrator", "main"));
        assertEquals("ph.control.orchestrator-status.main", rabbit.controllerStatusQueue("ph.control", "main"));
        assertEquals("ph.debug.alpha.processor.12345678", RabbitResourceNames.debugTapQueue("alpha", "processor", "12345678-1234-1234-1234-123456789abc"));
    }

}
