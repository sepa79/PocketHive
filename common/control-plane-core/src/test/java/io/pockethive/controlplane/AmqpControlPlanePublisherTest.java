package io.pockethive.controlplane;

import io.pockethive.control.ControlSignal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.observability.ControlPlaneJson;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmqpControlPlanePublisherTest {

    @Test
    void rejectsInvalidEnvelopeRoutingBeforeTouchingAmqp() {
        AmqpTemplate template = mock(AmqpTemplate.class);
        AmqpControlPlanePublisher publisher = new AmqpControlPlanePublisher(
            template, "ph.control", ControlPlaneCodec.create());
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "swarm", "role", "inst", "origin", "corr", "id", null);

        assertThatThrownBy(() -> publisher.publishSignal(new SignalMessage(
            "signal.config-update.swarm.role.other", signal)))
            .isInstanceOf(io.pockethive.controlplane.codec.ControlPlaneContractException.class)
            .hasMessageContaining("routing key");
        verifyNoInteractions(template);
    }

    @Test
    void forwardsSignalsAndEvents() throws Exception {
        AmqpTemplate template = mock(AmqpTemplate.class);
        AmqpControlPlanePublisher publisher = new AmqpControlPlanePublisher(
            template, "ph.control", ControlPlaneCodec.create());

	        ControlSignal signal = ControlSignal.forInstance(
	            "config-update", "swarm", "role", "inst", "origin", "corr", "id",
	            null);
        publisher.publishSignal(new SignalMessage("signal.config-update.swarm.role.inst", signal));

        var signalCaptor = forClass(Object.class);
        verify(template).convertAndSend(eq("ph.control"), eq("signal.config-update.swarm.role.inst"), signalCaptor.capture());

        assertThat(signalCaptor.getValue()).isInstanceOf(String.class);
        ObjectMapper mapper = ControlPlaneJson.mapper();
        ControlSignal parsed = mapper.readValue(signalCaptor.getValue().toString(), ControlSignal.class);
        assertThat(parsed.type()).isEqualTo("config-update");
        assertThat(parsed.scope().swarmId()).isEqualTo("swarm");
    }
}
