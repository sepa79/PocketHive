package io.pockethive.orchestrator.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmPlanRegistryTest {

    @Test
    void suppliesPlanForControllerReadyWhileSwarmMetadataPersists() {
        SwarmPlanRegistry plans = new SwarmPlanRegistry();
        SwarmStore swarms = new SwarmStore();
        List<Bee> bees = List.of(new Bee("generator", "bee-image", Work.ofDefaults("in", "out"), Map.of()));
        SwarmTemplateMetadata metadata = new SwarmTemplateMetadata("template-1", "controller-image", bees);
        Swarm swarm = new Swarm("swarm-1", "controller-1", "container-1", "run-1");
        swarm.attachTemplate(metadata);
        swarms.register(swarm);

        SwarmPlan plan = new SwarmPlan("swarm-1", bees);
        plans.register("controller-1", plan);

        assertEquals(plan, plans.find("controller-1").orElseThrow());

        SwarmPlan removed = plans.remove("controller-1").orElseThrow();
        assertEquals(plan, removed);

        Swarm remaining = swarms.find("swarm-1").orElseThrow();
        assertNotNull(remaining.templateMetadata());
        assertEquals("template-1", remaining.templateId());
        assertEquals("controller-image", remaining.controllerImage());
        assertEquals(bees, remaining.bees());

        swarms.remove("swarm-1");
        assertTrue(swarms.find("swarm-1").isEmpty());
    }
}
