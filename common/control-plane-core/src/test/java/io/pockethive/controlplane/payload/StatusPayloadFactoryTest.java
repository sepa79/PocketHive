package io.pockethive.controlplane.payload;

import static io.pockethive.controlplane.payload.JsonFixtureAssertions.ANY_VALUE;
import static io.pockethive.controlplane.payload.JsonFixtureAssertions.assertMatchesFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusPayloadFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final RoleContext context = new RoleContext("swarm-1", "processor", "instance-7");

    @Test
    void buildsSnapshotPayload() throws IOException {
        StatusPayloadFactory factory = new StatusPayloadFactory(context);
        String json = factory.snapshot(builder -> builder
            .traffic("ph.traffic")
            .workIn("work.in")
            .workRoutes("rk.work")
            .workOut("rk.out")
            .controlIn("ctrl.queue")
            .controlRoutes("rk.ctrl")
            .controlOut("rk.status")
            .enabled(true)
            .tps(42)
            .data("startedAt", Instant.parse("2024-01-01T00:00:00Z"))
            .runtime(Map.of(
                "templateId", "template-1",
                "runId", "run-1",
                "containerId", "container-1",
                "image", "worker:latest",
                "stackName", "ph-swarm-1"))
            .watermark(Instant.parse("2024-01-01T00:00:00Z"))
            .totals(3, 2, 2, 3)
            .data("baseUrl", "https://example"));

        assertEquals("instance-7", MAPPER.readTree(json).get("origin").asText());
        assertMatchesFixture("/io/pockethive/controlplane/payload/status-snapshot.json", normalise(json));
    }

    @Test
    void buildsDeltaPayload() throws IOException {
        StatusPayloadFactory factory = new StatusPayloadFactory(context);
        String json = factory.delta(builder -> builder
            .controlOut("rk.status")
            .enabled(false)
            .tps(7)
            .runtime(Map.of("templateId", "template-1", "runId", "run-1")));

        JsonNode node = MAPPER.readTree(json);
        assertEquals("instance-7", node.get("origin").asText());
        JsonNode data = node.path("data");
        assertThat(data.has("startedAt")).isFalse();
        assertThat(data.has("config")).isFalse();
        assertThat(data.has("io")).isFalse();
        assertMatchesFixture("/io/pockethive/controlplane/payload/status-delta.json", normalise(json));
    }

    private static String normalise(String json) throws IOException {
        ObjectNode node = (ObjectNode) MAPPER.readTree(json);
        node.put("timestamp", ANY_VALUE);
        JsonNode data = node.path("data");
        if (data.isObject()) {
            JsonNode context = data.path("context");
            if (context.isObject()) {
                ((ObjectNode) context).put("location", ANY_VALUE);
            }
        }
        return MAPPER.writeValueAsString(node);
    }
}
