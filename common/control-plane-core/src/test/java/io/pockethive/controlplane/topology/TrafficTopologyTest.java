package io.pockethive.controlplane.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrafficTopologyTest {
    @Test
    void buildsTrafficQueueNamesFromSharedTopology() {
        TrafficTopology topology = new TrafficTopology("ph.sw1.hive", "ph.sw1");

        assertThat(topology.hiveExchange()).isEqualTo("ph.sw1.hive");
        assertThat(topology.queuePrefix()).isEqualTo("ph.sw1");
        assertThat(topology.queueName("gen")).isEqualTo("ph.sw1.gen");
        assertThat(topology.queue("final")).isEqualTo(new QueueDescriptor("ph.sw1.final", Set.of()));
    }

    @Test
    void buildsOrderedDistinctQueueNames() {
        TrafficTopology topology = new TrafficTopology("ph.sw1.hive", "ph.sw1");

        assertThat(topology.queueNames(List.of("gen", "final", "gen")))
            .containsExactly("ph.sw1.gen", "ph.sw1.final");
    }

    @Test
    void rejectsBlankSegments() {
        assertThatThrownBy(() -> new TrafficTopology(" ", "ph.sw1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hiveExchange");

        TrafficTopology topology = new TrafficTopology("ph.sw1.hive", "ph.sw1");
        assertThatThrownBy(() -> topology.queueName(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("suffix");
    }
}
