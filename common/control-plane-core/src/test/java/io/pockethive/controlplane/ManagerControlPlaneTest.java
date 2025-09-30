package io.pockethive.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.consumer.SelfFilter;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ManagerControlPlaneTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsProcessSignals() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        ManagerControlPlane plane = ManagerControlPlane.builder(publisher, mapper)
            .identity(new io.pockethive.controlplane.ControlPlaneIdentity("swarm", "role", "inst"))
            .build();

        ControlSignal signal = new ControlSignal("config-update", "corr", "idem", "swarm", "role", "inst", null, CommandTarget.ALL, null);
        String payload = mapper.writeValueAsString(signal);

        AtomicBoolean handled = new AtomicBoolean();
        boolean result = plane.consume(payload, "sig.config-update.role.inst", envelope -> handled.set(true));

        assertThat(result).isTrue();
        assertThat(handled).isTrue();
    }

    @Test
    void canSkipSelfSignalsWhenConfigured() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        ManagerControlPlane plane = ManagerControlPlane.builder(publisher, mapper)
            .identity(new io.pockethive.controlplane.ControlPlaneIdentity("swarm", "role", "inst"))
            .selfFilter(SelfFilter.skipSelfInstance())
            .build();

        ControlSignal signal = new ControlSignal("config-update", "corr", "idem", "swarm", "role", "inst", "inst", CommandTarget.ALL, null);
        String payload = mapper.writeValueAsString(signal);

        AtomicBoolean handled = new AtomicBoolean();
        boolean result = plane.consume(payload, "sig.config-update.role.inst", envelope -> handled.set(true));

        assertThat(result).isFalse();
        assertThat(handled).isFalse();
    }

    @Test
    void processesSignalsFromOtherOriginsWhenSkippingSelf() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        ManagerControlPlane plane = ManagerControlPlane.builder(publisher, mapper)
            .identity(new io.pockethive.controlplane.ControlPlaneIdentity("swarm", "role", "inst"))
            .selfFilter(SelfFilter.skipSelfInstance())
            .build();

        ControlSignal signal = new ControlSignal("config-update", "corr", "idem", "swarm", "role", "inst", "other-inst", CommandTarget.ALL, null);
        String payload = mapper.writeValueAsString(signal);

        AtomicBoolean handled = new AtomicBoolean();
        boolean result = plane.consume(payload, "sig.config-update.role.inst", envelope -> handled.set(true));

        assertThat(result).isTrue();
        assertThat(handled).isTrue();
    }

    @Test
    void duplicateCacheConfiguredWhenRequested() {
        RecordingPublisher publisher = new RecordingPublisher();
        ManagerControlPlane plane = ManagerControlPlane.builder(publisher, mapper)
            .identity(new io.pockethive.controlplane.ControlPlaneIdentity("swarm", "role", "inst"))
            .duplicateCache(Duration.ofSeconds(30), 16)
            .build();

        plane.publishSignal(new SignalMessage("sig.config-update.role.inst", "{}"));

        assertThat(publisher.lastRoutingKey).isEqualTo("sig.config-update.role.inst");
        assertThat(publisher.lastPayload).isEqualTo("{}");
    }

    private static final class RecordingPublisher implements ControlPlanePublisher {
        private String lastRoutingKey;
        private Object lastPayload;

        @Override
        public void publishSignal(SignalMessage message) {
            this.lastRoutingKey = message.routingKey();
            this.lastPayload = message.payload();
        }

        @Override
        public void publishEvent(io.pockethive.controlplane.messaging.EventMessage message) {
        }
    }
}
