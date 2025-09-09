package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReadyEmitterTest {
    @Mock
    RabbitTemplate rabbit;

    @Test
    void emitsReadyEventOnStartup() {
        ReadyEmitter emitter = new ReadyEmitter(rabbit, "inst");
        emitter.emit();
        verify(rabbit).convertAndSend(Topology.CONTROL_EXCHANGE, "ev.ready.herald.inst", "");
    }
}
