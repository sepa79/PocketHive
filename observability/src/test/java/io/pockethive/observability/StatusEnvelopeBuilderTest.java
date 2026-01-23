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
                .data("runtime", Map.of(
                    "runId", "run-1",
                    "containerId", "container-1",
                    "image", "worker:latest",
                    "stackName", "ph-sw1"))
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
                .data("runtime", Map.of(
                    "runId", "run-1",
                    "containerId", "container-1",
                    "image", "worker:latest",
                    "stackName", "ph-sw1"))
                .queueStats(stats)
                .toJson();

        JsonNode node = new ObjectMapper().readTree(json);
        JsonNode queueStats = node.path("data").path("io").path("work").path("queueStats");
        assertTrue(queueStats.isObject());
        assertEquals(3, queueStats.get("ph.work.alpha.generator").get("depth").asInt());
        assertEquals(2, queueStats.get("ph.work.alpha.generator").get("consumers").asInt());
        assertEquals(12.5, queueStats.get("ph.work.alpha.generator").get("oldestAgeSec").asDouble());
    }

    @Test
    void serialisesIoStateWhenProvided() throws Exception {
        String json = new StatusEnvelopeBuilder()
                .type("status-full")
                .role("generator")
                .instance("gen-1")
                .origin("gen-1")
                .enabled(true)
                .tps(0)
                .data("startedAt", Instant.parse("2024-01-01T00:00:00Z"))
                .data("runtime", Map.of(
                    "runId", "run-1",
                    "containerId", "container-1",
                    "image", "worker:latest",
                    "stackName", "ph-sw1"))
                .ioWorkState("out-of-data", "ok", Map.of("dataset", "redis:users"))
                .filesystemEnabled(true)
                .ioFilesystemState("ok", "ok", null)
                .toJson();

        JsonNode node = new ObjectMapper().readTree(json);
        JsonNode ioState = node.path("data").path("ioState");
        assertEquals("out-of-data", ioState.path("work").path("input").asText());
        assertEquals("ok", ioState.path("work").path("output").asText());
        assertEquals("redis:users", ioState.path("work").path("context").path("dataset").asText());
        assertEquals("ok", ioState.path("filesystem").path("input").asText());
        assertEquals("ok", ioState.path("filesystem").path("output").asText());
    }

    @Test
    void rejectsInvalidIoStates() {
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder()
                .type("status-delta")
                .role("generator")
                .instance("gen-1")
                .origin("gen-1")
                .enabled(true)
                .tps(0);

        assertThrows(IllegalArgumentException.class, () -> builder.ioWorkState("nope", "ok", null));
        assertThrows(IllegalArgumentException.class, () -> builder.ioFilesystemState("ok", "nope", null));
    }

    @Test
    void canDisableWorkPlaneIo() throws Exception {
        String json = new StatusEnvelopeBuilder()
            .workPlaneEnabled(false)
            .type("status-delta")
            .role("orchestrator")
            .instance("orch-1")
            .origin("orch-1")
            .enabled(true)
            .tps(0)
            .toJson();

        JsonNode node = new ObjectMapper().readTree(json);
        JsonNode ioState = node.path("data").path("ioState");
        assertTrue(ioState.path("work").isMissingNode());
        assertTrue(ioState.path("filesystem").isMissingNode());
        assertEquals(0, ioState.size());
    }
}
