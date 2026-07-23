package io.pockethive.swarm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkBindingTest {

    @Test
    void bindingRequiresExplicitRequestedAndEffectiveModes() {
        IllegalArgumentException requested = assertThrows(IllegalArgumentException.class, () -> new NetworkBinding(
            "sw-1", "sut-a", null, null, NetworkMode.DIRECT, "orchestrator",
            Instant.parse("2026-03-08T01:00:00Z"), List.of()));
        assertEquals("networkMode must not be null", requested.getMessage());

        IllegalArgumentException effective = assertThrows(IllegalArgumentException.class, () -> new NetworkBinding(
            "sw-1", "sut-a", NetworkMode.DIRECT, null, null, "orchestrator",
            Instant.parse("2026-03-08T01:00:00Z"), List.of()));
        assertEquals("effectiveMode must not be null", effective.getMessage());
    }

    @Test
    void proxiedBindingRequiresProfile() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkBinding(
            "sw-1",
            "sut-a",
            NetworkMode.PROXIED,
            null,
            NetworkMode.PROXIED,
            "orchestrator",
            Instant.parse("2026-03-08T01:00:00Z"),
            List.of()));
    }

    @Test
    void directBindingRejectsProfile() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkBinding(
            "sw-1",
            "sut-a",
            NetworkMode.DIRECT,
            "passthrough",
            NetworkMode.DIRECT,
            "orchestrator",
            Instant.parse("2026-03-08T01:00:00Z"),
            List.of()));
    }

    @Test
    void requestRequiresMatchingResolvedSut() {
        ResolvedSutEnvironment resolved = new ResolvedSutEnvironment("sut-a", "SUT A", null, java.util.Map.of());

        NetworkBindingRequest request = new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolved);

        assertEquals("sut-a", request.sutId());

        assertThrows(IllegalArgumentException.class, () -> new NetworkBindingRequest(
            "sut-b",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolved));
    }
}
