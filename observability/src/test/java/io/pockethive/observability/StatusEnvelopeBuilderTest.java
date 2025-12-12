package io.pockethive.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatusEnvelopeBuilderTest {
    @Test
    void includesSwarmIdWhenProvided() throws Exception {
        String json = new StatusEnvelopeBuilder()
                .type("status-full")
                .role("orchestrator")
                .instance("inst")
                .origin("inst")
                .swarmId("sw1")
                .enabled(true)
                .tps(0)
                .data("startedAt", Instant.parse("2024-01-01T00:00:00Z"))
                .toJson();
        JsonNode node = new ObjectMapper().readTree(json);
        assertEquals("sw1", node.path("scope").path("swarmId").asText());
    }

    @Test
    void storesOrigin() throws Exception {
        String json = new StatusEnvelopeBuilder()
                .type("status-delta")
                .role("processor")
                .instance("proc-1")
                .origin("proc-1")
                .enabled(true)
                .tps(0)
                .toJson();
        JsonNode node = new ObjectMapper().readTree(json);
        assertEquals("proc-1", node.get("origin").asText());
    }

    @Test
    void rejectsBlankOrigin() {
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.origin(" "));
    }

    @Test
    void serialisesQueueStatsWhenProvided() throws Exception {
        Map<String, Map<String, Object>> stats = Map.of(
                "ph.work.alpha.generator", Map.of("depth", 3, "consumers", 2, "oldestAgeSec", 12.5)
        );

        String json = new StatusEnvelopeBuilder()
                .type("status-full")
                .role("generator")
                .instance("gen-1")
                .origin("gen-1")
                .enabled(true)
                .tps(0)
                .data("startedAt", Instant.parse("2024-01-01T00:00:00Z"))
                .queueStats(stats)
                .toJson();

        JsonNode node = new ObjectMapper().readTree(json);
        JsonNode queueStats = node.path("data").path("io").path("work").path("queueStats");
        assertTrue(queueStats.isObject());
        assertEquals(3, queueStats.get("ph.work.alpha.generator").get("depth").asInt());
        assertEquals(2, queueStats.get("ph.work.alpha.generator").get("consumers").asInt());
        assertEquals(12.5, queueStats.get("ph.work.alpha.generator").get("oldestAgeSec").asDouble());
    }
}
