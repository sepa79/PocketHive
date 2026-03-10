package io.pockethive.swarm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NetworkProfileTest {

    @Test
    void trimsAndCopiesValues() {
        NetworkProfile profile = new NetworkProfile(
            " latency-250ms ",
            " Latency 250ms ",
            List.of(new NetworkFault(" latency ", Map.of("latency", 250))),
            List.of(" payments ", " auth "));

        assertEquals("latency-250ms", profile.id());
        assertEquals("Latency 250ms", profile.name());
        assertEquals("latency", profile.faults().getFirst().type());
        assertEquals(250, profile.faults().getFirst().config().get("latency"));
        assertEquals(List.of("payments", "auth"), profile.targets());
    }

    @Test
    void defaultsCollectionsToEmpty() {
        NetworkProfile profile = new NetworkProfile("passthrough", "Passthrough", null, null);

        assertEquals(List.of(), profile.faults());
        assertEquals(List.of(), profile.targets());
    }

    @Test
    void rejectsBlankCoreFields() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkProfile(" ", "Passthrough", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NetworkProfile("passthrough", " ", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new NetworkProfile("passthrough", "Passthrough", List.of(), List.of(" ")));
        assertThrows(IllegalArgumentException.class,
            () -> new NetworkFault(" ", Map.of("latency", 250)));
    }
}
