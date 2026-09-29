package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.artemis.config.ArtemisEnvironmentKeys;
import io.pockethive.artemis.topology.ArtemisResourceKind;
import io.pockethive.artemis.topology.ArtemisResourceNames;
import io.pockethive.artemis.work.ArtemisWorkTopologyResolver;
import io.pockethive.topology.work.WorkResourceIdentity;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArtemisTopologyTest {
    private final ArtemisWorkTopologyResolver resolver = new ArtemisWorkTopologyResolver(new ArtemisResourceNames("ph"));

    @Test
    void delimiterCharactersCannotMakeTwoSwarmsShareAChannel() {
        var first = resolver.resolve("a.b", Set.of("c")).channel("c");
        var second = resolver.resolve("a", Set.of("b.c")).channel("b.c");
        assertThat(first.inputAddress()).isEqualTo("ph.a%2Eb.c").isNotEqualTo(second.inputAddress());
        assertThat(new ArtemisResourceNames("p.h").channel("a", "b")).isNotEqualTo("p.h.a.b");
    }

    @Test
    void resourcesAndConsumerProjectionsRetainOneResolvedAddress() {
        var topology = resolver.resolve("swarm", Set.of("jobs/next"));
        var channel = topology.channel("jobs/next");
        assertThat(channel.inputAddress()).isEqualTo("ph.swarm.jobs%2Fnext");
        assertThat(channel.inputEnvironment()).containsEntry(ArtemisEnvironmentKeys.INPUT_QUEUE, channel.inputAddress());
        assertThat(channel.outputEnvironment()).containsEntry(ArtemisEnvironmentKeys.OUTPUT_ADDRESS, channel.outputAddress());
        assertThat(topology.resources()).containsExactly(
            ArtemisResourceKind.ADDRESS.identity(channel.outputAddress()), channel.resource());
        assertThat(channel.inputStatus()).containsEntry("queue", channel.inputAddress());
    }

    @Test
    void rejectsAmbiguousNormalizedChannelsAndMissingSwarm() {
        assertThatThrownBy(() -> resolver.resolve("swarm", Set.of("jobs", " jobs ")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same");
        assertThatThrownBy(() -> resolver.resolve(" ", Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void channelNamesCannotIntroduceArtemisRoutingWildcards() {
        var topology = resolver.resolve("swarm", Set.of("*", "#"));
        assertThat(topology.channel("*").inputAddress()).isEqualTo("ph.swarm.%2A");
        assertThat(topology.channel("#").inputAddress()).isEqualTo("ph.swarm.%23");
    }

    @Test
    void resourceUrisRoundTripNamesWithoutChangingTheirScopeOrKind() {
        for (var kind : ArtemisResourceKind.values()) {
            var resource = kind.identity("ph.swarm.jobs%2Fnext +:/ż");
            String uri = ArtemisResourceNames.resourceAddress(resource);
            assertThat(ArtemisResourceNames.identify(uri)).isEqualTo(resource);
        }
        assertThatThrownBy(() -> ArtemisResourceNames.identify("rabbit://queue/jobs")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtemisResourceNames.identify("artemis://queue/jobs?force=true")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArtemisResourceNames.resourceAddress(new WorkResourceIdentity("RABBITMQ", "QUEUE", "jobs")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
