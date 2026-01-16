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
        Topology topology = new Topology(1, List.of(
            new TopologyEdge(
                "e1",
                new TopologyEndpoint("genA", "out"),
                new TopologyEndpoint("modA", "in"),
                new TopologySelector("predicate", "payload.priority >= 50"))
        ));
        SwarmPlan plan = new SwarmPlan("swarm-1", List.of(
            new Bee("genA", "generator", "img", Work.ofDefaults("in", "out"),
                List.of(new BeePort("out", "out")), Map.of("K", "V"), Map.of()),
            new Bee("modA", "moderator", "img2", Work.ofDefaults("in", "out"),
                List.of(new BeePort("in", "in")), Map.of(), Map.of())
        ), topology, new TrafficPolicy(guard), null, null);

        String json = mapper.writeValueAsString(plan);
        SwarmPlan restored = mapper.readValue(json, SwarmPlan.class);

        assertEquals("swarm-1", restored.id());
        assertEquals(2, restored.bees().size());
        Map<String, Bee> beesById = restored.bees().stream()
            .collect(java.util.stream.Collectors.toMap(Bee::id, bee -> bee));
        Bee generator = beesById.get("genA");
        Bee moderator = beesById.get("modA");
        assertNotNull(generator);
        assertNotNull(moderator);
        assertEquals("generator", generator.role());
        assertEquals("img", generator.image());
        assertNotNull(generator.env());
        assertEquals("V", generator.env().get("K"));
        assertEquals("moderator", moderator.role());
        assertEquals("img2", moderator.image());
        assertNotNull(restored.topology());
        assertEquals(1, restored.topology().edges().size());
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
