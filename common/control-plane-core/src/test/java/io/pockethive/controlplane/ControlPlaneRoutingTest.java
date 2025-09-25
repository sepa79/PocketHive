package io.pockethive.controlplane;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneRoutingTest {

    @Test
    void signalRoutingUsesAllPlaceholder() {
        String rk = ControlPlaneRouting.signal("config-update", null, "generator", null);
        assertThat(rk).isEqualTo("sig.config-update.ALL.generator.ALL");
    }

    @Test
    void eventRoutingIncludesScope() {
        ConfirmationScope scope = new ConfirmationScope("swarmA", "generator", "gen-1");
        String rk = ControlPlaneRouting.event("ready", "config-update", scope);
        assertThat(rk).isEqualTo("ev.ready.config-update.swarmA.generator.gen-1");
    }
}
