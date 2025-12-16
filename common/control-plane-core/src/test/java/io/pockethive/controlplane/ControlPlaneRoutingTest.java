package io.pockethive.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.payload.JsonFixtureAssertions;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneRoutingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void signalRoutingUsesAllPlaceholder() {
        String rk = ControlPlaneRouting.signal("config-update", null, "generator", null);
        assertThat(rk).isEqualTo("signal.config-update.ALL.generator.ALL");
    }

    @Test
    void eventRoutingIncludesScope() {
        ConfirmationScope scope = new ConfirmationScope("swarmA", "generator", "gen-1");
        String rk = ControlPlaneRouting.event("outcome", "config-update", scope);
        assertThat(rk).isEqualTo("event.outcome.config-update.swarmA.generator.gen-1");
    }

    @Test
    void eventRoutingWithCombinedType() {
        ConfirmationScope scope = new ConfirmationScope("swarmA", "generator", "gen-1");
        String rk = ControlPlaneRouting.event("metric", "status-delta", scope);
        assertThat(rk).isEqualTo("event.metric.status-delta.swarmA.generator.gen-1");
    }

    @Test
    void parseSignalExtractsSegments() {
        ControlPlaneRouting.RoutingKey key = ControlPlaneRouting.parseSignal("signal.config-update.swarmA.generator.gen-1");
        assertThat(key.prefix()).isEqualTo("signal");
        assertThat(key.type()).isEqualTo("config-update");
        assertThat(key.swarmId()).isEqualTo("swarmA");
        assertThat(key.role()).isEqualTo("generator");
        assertThat(key.instance()).isEqualTo("gen-1");
    }

    @Test
    void parseEventSupportsCompositeTypes() {
        ControlPlaneRouting.RoutingKey key = ControlPlaneRouting.parseEvent("event.outcome.config-update.swarmA.generator.gen-1");
        assertThat(key.type()).isEqualTo("outcome.config-update");
        assertThat(key.matchesType("outcome.config-update")).isTrue();
        assertThat(key.matchesSwarm("swarmA")).isTrue();
        assertThat(key.matchesRole("generator")).isTrue();
        assertThat(key.matchesInstance("gen-1")).isTrue();
    }

    @Test
    void routingDslMatchesGoldenFixture() throws IOException {
        ConfirmationScope scope = new ConfirmationScope("swarmA", "generator", "gen-1");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("signals", Map.of(
            "broadcast", ControlPlaneRouting.signal("config-update", null, "generator", null),
            "scoped", ControlPlaneRouting.signal("status-request", "swarmA", "generator", "gen-1")
        ));
        document.put("events", Map.of(
            "outcome", ControlPlaneRouting.event("outcome", "config-update", scope),
            "status", ControlPlaneRouting.event("metric", "status-delta", scope)
        ));
        document.put("parsed", Map.of(
            "signal", describe(ControlPlaneRouting.parseSignal("signal.config-update.swarmA.generator.gen-1")),
            "event", describe(ControlPlaneRouting.parseEvent("event.outcome.config-update.swarmA.generator.gen-1"))
        ));

        String json = MAPPER.writeValueAsString(document);
        JsonFixtureAssertions.assertMatchesFixture(
            "/io/pockethive/controlplane/routing/routing-dsl.json",
            json);
    }

    private Map<String, Object> describe(ControlPlaneRouting.RoutingKey key) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("prefix", key.prefix());
        node.put("type", key.type());
        node.put("swarmId", key.swarmId());
        node.put("role", key.role());
        node.put("instance", key.instance());
        node.put("matches", Map.of(
            "type", key.matchesType(key.type()),
            "swarm", key.matchesSwarm(key.swarmId()),
            "role", key.matchesRole(key.role()),
            "instance", key.matchesInstance(key.instance())
        ));
        return node;
    }
}
