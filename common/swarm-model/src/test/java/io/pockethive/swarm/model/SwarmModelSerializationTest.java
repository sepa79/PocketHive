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
        BufferGuardPolicy guard = new BufferGuardPolicy(
            true,
            "genToMod",
            200,
            150,
            260,
            "5s",
            3,
            new BufferGuardPolicy.Adjustment(10, 15, 5, 5000),
            new BufferGuardPolicy.Prefill(true, "2m", 20),
            new BufferGuardPolicy.Backpressure("modToProc", 500, 300, 15));
        SwarmPlan plan = new SwarmPlan("swarm-1", List.of(
            new Bee("generator", "img", new Work("in", "out"), Map.of("K", "V"))
        ), new TrafficPolicy(guard));

        String json = mapper.writeValueAsString(plan);
        SwarmPlan restored = mapper.readValue(json, SwarmPlan.class);

        assertEquals("swarm-1", restored.id());
        assertEquals(1, restored.bees().size());
        Bee bee = restored.bees().getFirst();
        assertEquals("generator", bee.role());
        assertEquals("img", bee.image());
        assertNotNull(bee.env());
        assertEquals("V", bee.env().get("K"));
        assertNotNull(restored.trafficPolicy());
        assertNotNull(restored.trafficPolicy().bufferGuard());
        assertEquals("genToMod", restored.trafficPolicy().bufferGuard().queueAlias());
    }

    @Test
    void templateDefaultsToEmptyBees() {
        SwarmTemplate template = new SwarmTemplate("controller", null);

        assertNotNull(template.bees());
        assertEquals(0, template.bees().size());
    }
}
