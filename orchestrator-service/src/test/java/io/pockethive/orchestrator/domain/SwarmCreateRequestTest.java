package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.NetworkMode;
import org.junit.jupiter.api.Test;

class SwarmCreateRequestTest {

    @Test
    void requestHasNoConstructorThatDeclaresNetworkModeForTheCaller() {
        assertThatThrownBy(() -> new SwarmCreateRequest(
            "tpl-1", "idem", null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("networkMode must be provided");
    }

    @Test
    void rejectsMissingNetworkModeInTheWireConstructor() {
        assertThatThrownBy(() -> new SwarmCreateRequest(
            "tpl-1", "idem", null, false, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("networkMode must be provided");
    }

    @Test
    void rejectsProfileWithoutProxiedMode() {
        assertThatThrownBy(() -> new SwarmCreateRequest(
            "tpl-1",
            "idem",
            null,
            null,
            null,
            null,
            NetworkMode.DIRECT,
            "passthrough"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("networkProfileId requires networkMode=PROXIED");
    }

    @Test
    void requiresProfileForProxiedMode() {
        assertThatThrownBy(() -> new SwarmCreateRequest(
            "tpl-1",
            "idem",
            null,
            null,
            null,
            null,
            NetworkMode.PROXIED,
            null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("networkProfileId must be provided when networkMode=PROXIED");
    }

    @Test
    void requiresSutForProxiedMode() {
        assertThatThrownBy(() -> new SwarmCreateRequest(
            "tpl-1",
            "idem",
            null,
            null,
            null,
            null,
            NetworkMode.PROXIED,
            "passthrough"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sutId must be provided when networkMode=PROXIED");
    }
}
