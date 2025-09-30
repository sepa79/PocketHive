package io.pockethive.controlplane.payload;

import static io.pockethive.controlplane.payload.JsonFixtureAssertions.ANY_VALUE;
import static io.pockethive.controlplane.payload.JsonFixtureAssertions.assertMatchesFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
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
            .tps(7));

        assertEquals("instance-7", MAPPER.readTree(json).get("origin").asText());
        assertMatchesFixture("/io/pockethive/controlplane/payload/status-delta.json", normalise(json));
    }

    private static String normalise(String json) throws IOException {
        ObjectNode node = (ObjectNode) MAPPER.readTree(json);
        node.put("messageId", ANY_VALUE);
        node.put("timestamp", ANY_VALUE);
        node.put("location", ANY_VALUE);
        return MAPPER.writeValueAsString(node);
    }
}
