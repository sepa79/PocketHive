package io.pockethive.worker.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.control.CommandState;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import org.junit.jupiter.api.Test;

class ControlPlaneTestFixturesTest {

    @Test
    void workerPropertiesPopulateFields() {
        WorkerControlPlaneProperties properties = ControlPlaneTestFixtures.workerProperties("swarm-1", " generator ", "worker-a");

        assertThat(properties.getSwarmId()).isEqualTo("swarm-1");
        assertThat(properties.getWorker().getRole()).isEqualTo("generator");
        assertThat(properties.getInstanceId()).isEqualTo("worker-a");
        assertThat(properties.getExchange()).isEqualTo("ph.control");
        assertThat(ControlPlaneTestFixtures.hiveExchange("swarm-1")).isEqualTo("ph.swarm-1.hive");
        assertThat(ControlPlaneTestFixtures.workerQueue("swarm-1", "generator")).isEqualTo("ph.swarm-1.gen");
        assertThat(ControlPlaneTestFixtures.workerQueue("swarm-1", "moderator")).isEqualTo("ph.swarm-1.mod");
        WorkerControlPlaneProperties.ControlPlane controlPlane = properties.getControlPlane();
        assertThat(controlPlane.getControlQueueName()).isEqualTo("ph.control.swarm-1.generator.worker-a");
        assertThat(controlPlane.getRoutes().configSignals())
            .contains("signal.config-update.swarm-1.generator.{instance}");
        assertThat(controlPlane.getRoutes().statusSignals())
            .contains("signal.status-request.swarm-1.generator.{instance}");
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
