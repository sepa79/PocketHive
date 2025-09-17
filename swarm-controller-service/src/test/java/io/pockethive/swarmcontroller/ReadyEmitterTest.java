package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
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
        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "ev.ready.swarm-controller.inst", "");
    }

    @Test
    void ignoresAmqpFailures() {
        ReadyEmitter emitter = new ReadyEmitter(rabbit, "inst");
        doThrow(new AmqpConnectException(new IllegalStateException("boom")))
            .when(rabbit)
            .convertAndSend(Topology.CONTROL_EXCHANGE, "ev.ready.swarm-controller.inst", "");
        assertDoesNotThrow(emitter::emit);
    }
}
