package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class ReadyEmitterTest {
    @Mock
    RabbitTemplate rabbit;

    @Test
    void emitsReadyEventOnStartup() {
        ReadyEmitter emitter = new ReadyEmitter(rabbit, "inst");
        emitter.emit();
        ConfirmationScope scope = ConfirmationScope.forInstance(Topology.SWARM_ID, "swarm-controller", "inst");
        String routingKey = ControlPlaneRouting.event("ready.swarm-controller", scope);
        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, "");
    }

    @Test
    void ignoresAmqpFailures() {
        ReadyEmitter emitter = new ReadyEmitter(rabbit, "inst");
        ConfirmationScope scope = ConfirmationScope.forInstance(Topology.SWARM_ID, "swarm-controller", "inst");
        String routingKey = ControlPlaneRouting.event("ready.swarm-controller", scope);
        doThrow(new AmqpConnectException(new IllegalStateException("boom")))
            .when(rabbit)
            .convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, "");
        assertDoesNotThrow(emitter::emit);
    }
}
