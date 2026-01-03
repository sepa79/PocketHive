package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReadyEmitterTest {
    @Mock
    ControlPlanePublisher publisher;

    @Test
    void emitsReadyEventOnStartup() throws Exception {
        ObjectMapper mapper = ControlPlaneJson.mapper();
        SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
        ReadyEmitter emitter = new ReadyEmitter(publisher, "inst", properties);
        emitter.emit();
        ConfirmationScope scope = ConfirmationScope.forInstance(properties.getSwarmId(), properties.getRole(), "inst");
        String routingKey = ControlPlaneRouting.event("outcome", properties.getRole(), scope);
        ArgumentCaptor<EventMessage> payload = ArgumentCaptor.forClass(EventMessage.class);
        verify(publisher).publishEvent(payload.capture());
        EventMessage message = payload.getValue();
        assertThat(message.routingKey()).isEqualTo(routingKey);
        assertThat(message.payload()).isInstanceOf(String.class);
        CommandOutcome outcome = mapper.readValue(message.payload().toString(), CommandOutcome.class);
        assertThat(outcome.kind()).isEqualTo("outcome");
        assertThat(outcome.type()).isEqualTo(properties.getRole());
        assertThat(outcome.scope()).isEqualTo(new ControlScope(properties.getSwarmId(), properties.getRole(), "inst"));
        assertThat(outcome.data()).containsEntry("status", "Ready");
    }

    @Test
    void ignoresAmqpFailures() {
        SwarmControllerProperties properties = SwarmControllerTestProperties.defaults();
        ReadyEmitter emitter = new ReadyEmitter(publisher, "inst", properties);
        doThrow(new RuntimeException("boom"))
            .when(publisher)
            .publishEvent(any(EventMessage.class));
        assertDoesNotThrow(emitter::emit);
    }
}
