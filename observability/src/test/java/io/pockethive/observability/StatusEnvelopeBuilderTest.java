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
                .swarmId("sw1")
                .toJson();
        JsonNode node = new ObjectMapper().readTree(json);
        assertEquals("sw1", node.get("swarmId").asText());
    }
}
