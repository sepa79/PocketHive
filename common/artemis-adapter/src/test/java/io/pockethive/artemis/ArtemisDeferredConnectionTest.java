package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.artemis.api.ArtemisWorkPlane;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtemisDeferredConnectionTest {
    @TempDir Path directory;

    @Test void firstResourceOperationConnectsAfterTheBrokerStarts() throws Exception {
        try (var broker = new EmbeddedArtemis(directory, false);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            var resources = plane.resources();
            assertThat(resources.connectionIdentity()).isEqualTo(broker.settings().identity());
            assertThat(resources.identify(resources.removalTarget(topology.channel("jobs").resource())))
                .isEqualTo(topology.channel("jobs").resource());
            broker.start();
            resources.ensure(topology);
            assertThat(resources.observeInput(topology.channel("jobs").inputAddress())).isPresent();
            for (var resource : topology.resources().reversed()) resources.remove(resource);
            for (var resource : topology.resources()) assertThat(resources.observe(resource)).isEmpty();
        }
    }

    @Test void unavailableBrokerFailsOperationsAndALaterExplicitOperationCanConnect() throws Exception {
        try (var broker = new EmbeddedArtemis(directory, false);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            var resources = plane.resources();
            assertThatThrownBy(() -> resources.ensure(topology)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> resources.observe(topology.channel("jobs").resource()))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> resources.remove(topology.channel("jobs").resource()))
                .isInstanceOf(IllegalStateException.class);
            assertThat(resources.appliedResources()).isEmpty();
            broker.start();
            resources.ensure(topology);
            assertThat(resources.observeInput(topology.channel("jobs").inputAddress())).isPresent();
            assertThat(resources.appliedResources()).containsExactly(topology.channel("jobs").resource());
        }
    }

    @Test void closingBeforeFirstUseIsTerminalEvenIfTheBrokerLaterStarts() throws Exception {
        try (var broker = new EmbeddedArtemis(directory, false)) {
            var plane = new ArtemisWorkPlane(broker.settings(), "ph");
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.close();
            plane.close();
            broker.start();
            assertThatThrownBy(() -> plane.resources().ensure(topology)).isInstanceOf(IllegalStateException.class);
            try (var observer = new ArtemisWorkPlane(broker.settings(), "ph")) {
                for (var resource : topology.resources()) assertThat(observer.resources().observe(resource)).isEmpty();
            }
        }
    }
}
