package io.pockethive.swarm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmModelSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsSwarmPlanWithEnv() throws Exception {
        SwarmPlan plan = new SwarmPlan("swarm-1", List.of(
            new Bee("generator", "img", new Work("in", "out"), Map.of("K", "V"))
        ));

        String json = mapper.writeValueAsString(plan);
        SwarmPlan restored = mapper.readValue(json, SwarmPlan.class);

        assertEquals("swarm-1", restored.id());
        assertEquals(1, restored.bees().size());
        Bee bee = restored.bees().getFirst();
        assertEquals("generator", bee.role());
        assertEquals("img", bee.image());
        assertNotNull(bee.env());
        assertEquals("V", bee.env().get("K"));
    }

    @Test
    void templateDefaultsToEmptyBees() {
        SwarmTemplate template = new SwarmTemplate("controller", null);

        assertNotNull(template.bees());
        assertEquals(0, template.bees().size());
    }
}
