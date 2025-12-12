package io.pockethive.controlplane;

import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AmqpControlPlanePublisherTest {

    @Test
    void forwardsSignalsAndEvents() {
        AmqpTemplate template = mock(AmqpTemplate.class);
        AmqpControlPlanePublisher publisher = new AmqpControlPlanePublisher(template, "ph.control");

        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "swarm", "role", "inst", "origin", "corr", "id", null);
        publisher.publishSignal(new SignalMessage("signal.config-update.role.inst", signal));
        publisher.publishEvent(new EventMessage("event.outcome.config-update.swarm.role.inst", "payload"));

        verify(template).convertAndSend("ph.control", "signal.config-update.role.inst", signal);
        verify(template).convertAndSend("ph.control", "event.outcome.config-update.swarm.role.inst", "payload");
    }
}
