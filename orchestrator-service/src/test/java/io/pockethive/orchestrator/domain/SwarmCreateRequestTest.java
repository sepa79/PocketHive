package io.pockethive.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.NetworkMode;
import org.junit.jupiter.api.Test;

class SwarmCreateRequestTest {

    @Test
    void defaultsMissingNetworkModeToDirect() {
        SwarmCreateRequest request = new SwarmCreateRequest(" tpl-1 ", " idem ", null);

        assertThat(request.templateId()).isEqualTo("tpl-1");
        assertThat(request.idempotencyKey()).isEqualTo("idem");
        assertThat(request.networkMode()).isEqualTo(NetworkMode.DIRECT);
        assertThat(request.networkProfileId()).isNull();
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
