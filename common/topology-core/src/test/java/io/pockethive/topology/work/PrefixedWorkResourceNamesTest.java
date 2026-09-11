package io.pockethive.topology.work;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PrefixedWorkResourceNamesTest {
    private final WorkResourceNamesPort names = new PrefixedWorkResourceNames();

    @Test
    void resolvesExplicitNamesWithoutInferringAlreadyPrefixedAliases() {
        assertEquals("ph.swarm.jobs", names.queueName(" ph.swarm ", " jobs "));
        assertEquals("ph.swarm.ph.swarm.jobs", names.queueName("ph.swarm", "ph.swarm.jobs"));
        assertEquals("ph.swarm.hive", names.exchangeName(" ph.swarm.hive "));
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
}
