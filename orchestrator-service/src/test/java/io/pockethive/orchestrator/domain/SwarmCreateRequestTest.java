package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.NetworkMode;
import org.junit.jupiter.api.Test;

class SwarmCreateRequestTest {

  @Test
    void typedFactoryRejectsMissingNetworkModeThroughTheCanonicalSchema() {
        assertThatThrownBy(() -> SwarmCreateRequest.of(
            "tpl-1", "idem", false, null, null, null, null))
            .isInstanceOf(io.pockethive.swarm.model.lifecycle.SwarmLifecycleContractException.class)
            .hasMessageStartingWith("Swarm create request schema validation failed:");
    }

    @Test
    void typedFactoryRejectsProfileWithoutProxiedMode() {
        assertThatThrownBy(() -> SwarmCreateRequest.of(
            "tpl-1",
            "idem",
            false,
            null,
            null,
            NetworkMode.DIRECT,
            "passthrough"))
            .isInstanceOf(io.pockethive.swarm.model.lifecycle.SwarmLifecycleContractException.class)
            .hasMessageStartingWith("Swarm create request schema validation failed:");
    }

    @Test
    void typedFactoryRequiresProfileForProxiedMode() {
        assertThatThrownBy(() -> SwarmCreateRequest.of(
            "tpl-1",
            "idem",
            false,
            null,
            null,
            NetworkMode.PROXIED,
            null))
            .isInstanceOf(io.pockethive.swarm.model.lifecycle.SwarmLifecycleContractException.class)
            .hasMessageStartingWith("Swarm create request schema validation failed:");
    }

    @Test
    void typedFactoryRequiresSutForProxiedMode() {
        assertThatThrownBy(() -> SwarmCreateRequest.of(
            "tpl-1",
            "idem",
            false,
            null,
            null,
            NetworkMode.PROXIED,
            "passthrough"))
            .isInstanceOf(io.pockethive.swarm.model.lifecycle.SwarmLifecycleContractException.class)
            .hasMessageStartingWith("Swarm create request schema validation failed:");
    }
}
