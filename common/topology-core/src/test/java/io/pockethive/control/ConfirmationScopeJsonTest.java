package io.pockethive.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConfirmationScopeJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void readyConfirmationDoesNotSerializeEmptyFlag() throws Exception {
        ReadyConfirmation confirmation = new ReadyConfirmation(
            Instant.parse("2024-01-01T00:00:00Z"),
            "corr",
            "idem",
            "swarm-create",
            ConfirmationScope.forSwarm("swarm-42"),
            CommandState.status("Ready")
        );

        String json = mapper.writeValueAsString(confirmation);
        assertFalse(json.contains("\"empty\""));

        ReadyConfirmation roundTrip = mapper.readValue(json, ReadyConfirmation.class);
        assertEquals("swarm-42", roundTrip.scope().swarmId());
        assertEquals("Ready", roundTrip.state().status());
    }
}
