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

    @Test
    void eventRoutingWithCombinedType() {
        ConfirmationScope scope = new ConfirmationScope("swarmA", "generator", "gen-1");
        String rk = ControlPlaneRouting.event("status-delta", scope);
        assertThat(rk).isEqualTo("ev.status-delta.swarmA.generator.gen-1");
    }

    @Test
    void parseSignalExtractsSegments() {
        ControlPlaneRouting.RoutingKey key = ControlPlaneRouting.parseSignal("sig.config-update.swarmA.generator.gen-1");
        assertThat(key.prefix()).isEqualTo("sig");
        assertThat(key.type()).isEqualTo("config-update");
        assertThat(key.swarmId()).isEqualTo("swarmA");
        assertThat(key.role()).isEqualTo("generator");
        assertThat(key.instance()).isEqualTo("gen-1");
    }

    @Test
    void parseEventSupportsCompositeTypes() {
        ControlPlaneRouting.RoutingKey key = ControlPlaneRouting.parseEvent("ev.ready.config-update.swarmA.generator.gen-1");
        assertThat(key.type()).isEqualTo("ready.config-update");
        assertThat(key.matchesType("ready.config-update")).isTrue();
        assertThat(key.matchesSwarm("swarmA")).isTrue();
        assertThat(key.matchesRole("generator")).isTrue();
        assertThat(key.matchesInstance("gen-1")).isTrue();
    }
}
