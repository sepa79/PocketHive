package io.pockethive.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusEnvelopeBuilderTest {
    @Test
    void includesSwarmIdWhenProvided() throws Exception {
        String json = new StatusEnvelopeBuilder()
                .kind("status-full")
                .role("orchestrator")
                .instance("inst")
                .origin("inst")
                .swarmId("sw1")
                .toJson();
        JsonNode node = new ObjectMapper().readTree(json);
        assertEquals("sw1", node.get("swarmId").asText());
    }

    @Test
    void storesOrigin() throws Exception {
        String json = new StatusEnvelopeBuilder()
                .kind("status-delta")
                .role("processor")
                .instance("proc-1")
                .origin("proc-1")
                .toJson();
        JsonNode node = new ObjectMapper().readTree(json);
        assertEquals("proc-1", node.get("origin").asText());
    }

    @Test
    void rejectsBlankOrigin() {
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.origin(" "));
    }
}
