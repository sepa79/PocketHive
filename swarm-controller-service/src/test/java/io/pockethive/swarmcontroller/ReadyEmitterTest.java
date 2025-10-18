package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReadyEmitterTest {
    @Mock
    RabbitTemplate rabbit;

    @Test
    void emitsReadyEventOnStartup() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
        ReadyEmitter emitter = new ReadyEmitter(rabbit, "inst", mapper, properties);
        emitter.emit();
        ConfirmationScope scope = ConfirmationScope.forInstance(properties.getSwarmId(), properties.getRole(), "inst");
        String routingKey = ControlPlaneRouting.event("ready." + properties.getRole(), scope);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(properties.getControlExchange()), eq(routingKey), payload.capture());
        ReadyConfirmation confirmation = mapper.readValue(payload.getValue(), ReadyConfirmation.class);
        assertThat(confirmation.signal()).isEqualTo(properties.getRole());
        assertThat(confirmation.scope()).isEqualTo(scope);
        assertThat(confirmation.state().status()).isEqualTo("Ready");
    }

    @Test
    void ignoresAmqpFailures() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
        ReadyEmitter emitter = new ReadyEmitter(rabbit, "inst", mapper, properties);
        ConfirmationScope scope = ConfirmationScope.forInstance(properties.getSwarmId(), properties.getRole(), "inst");
        String routingKey = ControlPlaneRouting.event("ready." + properties.getRole(), scope);
        doThrow(new AmqpConnectException(new IllegalStateException("boom")))
            .when(rabbit)
            .convertAndSend(eq(properties.getControlExchange()), eq(routingKey), any(Object.class));
        assertDoesNotThrow(emitter::emit);
    }
}
