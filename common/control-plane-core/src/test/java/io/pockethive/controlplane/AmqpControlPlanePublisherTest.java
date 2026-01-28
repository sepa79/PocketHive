package io.pockethive.controlplane;

import io.pockethive.control.ControlSignal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.observability.ControlPlaneJson;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AmqpControlPlanePublisherTest {

    @Test
    void forwardsSignalsAndEvents() throws Exception {
        AmqpTemplate template = mock(AmqpTemplate.class);
        AmqpControlPlanePublisher publisher = new AmqpControlPlanePublisher(template, "ph.control");

	        ControlSignal signal = ControlSignal.forInstance(
	            "config-update", "swarm", "role", "inst", "origin", "corr", "id",
	            null);
        publisher.publishSignal(new SignalMessage("signal.config-update.role.inst", signal));
        publisher.publishEvent(new EventMessage("event.outcome.config-update.swarm.role.inst", "payload"));

        var signalCaptor = forClass(Object.class);
        verify(template).convertAndSend(eq("ph.control"), eq("signal.config-update.role.inst"), signalCaptor.capture());
        verify(template).convertAndSend(eq("ph.control"), eq("event.outcome.config-update.swarm.role.inst"), eq("payload"));

        assertThat(signalCaptor.getValue()).isInstanceOf(String.class);
        ObjectMapper mapper = ControlPlaneJson.mapper();
        ControlSignal parsed = mapper.readValue(signalCaptor.getValue().toString(), ControlSignal.class);
        assertThat(parsed.type()).isEqualTo("config-update");
        assertThat(parsed.scope().swarmId()).isEqualTo("swarm");
    }
}
