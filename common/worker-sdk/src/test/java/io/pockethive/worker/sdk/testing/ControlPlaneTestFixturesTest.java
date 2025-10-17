package io.pockethive.worker.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.control.CommandState;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import org.junit.jupiter.api.Test;

class ControlPlaneTestFixturesTest {

    @Test
    void workerPropertiesPopulateFields() {
        ControlPlaneProperties properties = ControlPlaneTestFixtures.workerProperties("swarm-1", " generator ", "worker-a");

        assertThat(properties.getSwarmId()).isEqualTo("swarm-1");
        assertThat(properties.getWorker().getRole()).isEqualTo("generator");
        assertThat(properties.getIdentity().getInstanceId()).isEqualTo("worker-a");
    }

    @Test
    void workerTopologyRejectsBlankRole() {
        assertThatThrownBy(() -> ControlPlaneTestFixtures.workerTopology(" \t"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role");
    }

    @Test
    void workerEmitterPublishesReadyEvent() {
        CapturingPublisher publisher = new CapturingPublisher();
        ControlPlaneIdentity identity = ControlPlaneTestFixtures.workerIdentity("swarm-1", "generator", "worker-a");

        ControlPlaneEmitter emitter = ControlPlaneTestFixtures.workerEmitter(publisher, identity);
        ControlPlaneEmitter.ReadyContext context = ControlPlaneEmitter.ReadyContext
            .builder("config-update", "corr-1", "cmd-1", CommandState.status("Ready"))
            .result("ok")
            .build();

        emitter.emitReady(context);

        assertThat(publisher.lastEvent()).isNotNull();
        assertThat(publisher.lastEvent().routingKey()).contains("generator");
        assertThat(publisher.lastEvent().payload()).isNotNull();
    }

    private static final class CapturingPublisher implements ControlPlanePublisher {

        private EventMessage lastEvent;

        @Override
        public void publishSignal(SignalMessage message) {
            // signals are not used in these fixtures
        }

        @Override
        public void publishEvent(EventMessage message) {
            this.lastEvent = message;
        }

        EventMessage lastEvent() {
            return lastEvent;
        }
    }
}
