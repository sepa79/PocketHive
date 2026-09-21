package io.pockethive.rabbit.transport;

import io.pockethive.rabbit.api.RabbitMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpringRabbitTransportTest {
    private final RabbitTemplate template = mock(RabbitTemplate.class);

    @Test
    void publishesExplicitDestinationAndSelectedMetadataWithoutChangingPayload() {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        var message = new RabbitMessage(bytes, Map.of("trace", "value"), RabbitMessage.JSON, "UTF-8", false, null);
        bytes[0] = 0;
        new SpringRabbitPublisher(template).send("work.exchange", "selected.route", message);
        var captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("work.exchange"), eq("selected.route"), captor.capture());
        Message sent = captor.getValue();
        assertThat(sent.getBody()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
        assertThat(sent.getMessageProperties().getContentType()).isEqualTo(RabbitMessage.JSON);
        assertThat(sent.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
        assertThat(sent.getMessageProperties().getHeaders()).containsExactlyEntriesOf(Map.of("trace", "value"));
        assertThat(sent.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.NON_PERSISTENT);
        verifyNoMoreInteractions(template);
    }

    @Test
    void controlTextUsesUtf8AndPersistentDelivery() {
        new SpringRabbitPublisher(template).sendText("control", "route", "żółw");
        var captor = ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("control"), eq("route"), captor.capture());
        assertThat(captor.getValue().getBody()).isEqualTo("żółw".getBytes(StandardCharsets.UTF_8));
        assertThat(captor.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(captor.getValue().getMessageProperties().getContentType()).isEqualTo(RabbitMessage.TEXT);
    }

    @Test
    void receivesBodyAndBrokerMetadataAndDistinguishesEmptyQueue() {
        var properties = new MessageProperties();
        properties.setReceivedRoutingKey("received.route");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("trace", "context");
        when(template.receive("tap")).thenReturn(new Message(new byte[]{1, 2}, properties), null);
        var receiver = new SpringRabbitReceiver(template);
        RabbitMessage message = receiver.receive("tap").orElseThrow();
        assertThat(message.body()).containsExactly(1, 2);
        assertThat(message.receivedRoutingKey()).isEqualTo("received.route");
        assertThat(message.persistent()).isTrue();
        assertThat(message.headers()).containsEntry("trace", "context");
        assertThat(receiver.receive("tap")).isEmpty();
    }

    @Test
    void transportFailuresPropagateInsteadOfReportingSubmissionOrEmptyQueue() {
        var failure = new AmqpConnectException(new java.net.ConnectException("offline"));
        doThrow(failure).when(template).send(anyString(), anyString(), any(Message.class));
        when(template.receive("tap")).thenThrow(failure);
        assertThatThrownBy(() -> new SpringRabbitPublisher(template).sendText("control", "route", "body")).isSameAs(failure);
        assertThatThrownBy(() -> new SpringRabbitReceiver(template).receive("tap")).isSameAs(failure);
    }
}
