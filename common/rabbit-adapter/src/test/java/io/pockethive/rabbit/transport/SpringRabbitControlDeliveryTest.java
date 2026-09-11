package io.pockethive.rabbit.transport;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import io.pockethive.rabbit.api.RabbitListenerBinding;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitSubscription;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpringRabbitControlDeliveryTest {
    @ParameterizedTest
    @ValueSource(strings = {"ACK", "REJECT", "REQUEUE"})
    void deliversAndSettlesUsingControlPolicyIndependentOfWorkTuning(String outcome) throws Exception {
        var channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.queueDeclarePassive("control")).thenReturn(new AMQP.Queue.DeclareOk.Builder().queue("control").build());
        var consumer = new AtomicReference<Consumer>();
        doAnswer(call -> {
            Consumer registered = call.getArgument(6);
            consumer.set(registered);
            registered.handleConsumeOk("control-tag");
            return "control-tag";
        }).when(channel).basicConsume(eq("control"), eq(false), anyString(), eq(false), eq(false), anyMap(), any(Consumer.class));
        doAnswer(call -> { consumer.get().handleCancelOk("control-tag"); return null; }).when(channel).basicCancel("control-tag");
        var connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        when(connection.createChannel(false)).thenReturn(channel);
        var connections = mock(ConnectionFactory.class);
        when(connections.createConnection()).thenReturn(connection);
        var configurer = mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        doAnswer(call -> {
            SimpleRabbitListenerContainerFactory factory = call.getArgument(0);
            factory.setConnectionFactory(connections);
            factory.setPrefetchCount(7);
            factory.setConcurrentConsumers(1);
            factory.setAutoStartup(false);
            factory.setContainerCustomizer(container -> {
                container.setAutoDeclare(false);
                container.setReceiveTimeout(10L);
                container.setShutdownTimeout(1000L);
            });
            return null;
        }).when(configurer).configure(any(), eq(connections));
        var registry = new RabbitListenerEndpointRegistry();
        var listeners = new SpringRabbitListeners(registry, connections, connections, configurer);
        var delivered = new AtomicReference<RabbitMessage>();
        try {
            listeners.register(new RabbitSubscription("work", "jobs", 29, 3, false, false), message -> {});
            listeners.register(new RabbitListenerBinding("control", "control", message -> {
                delivered.set(message);
                if (outcome.equals("REJECT")) throw new IllegalArgumentException("invalid contract");
                if (outcome.equals("REQUEUE")) throw new IllegalStateException("transient failure");
            }, failure -> {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof IllegalArgumentException) return true;
                }
                return false;
            }));
            listeners.start("control");
            verify(channel, timeout(2000)).basicQos(7, false);
            verify(channel, timeout(2000)).basicConsume(eq("control"), eq(false), anyString(), eq(false), eq(false), anyMap(), any(Consumer.class));
            consumer.get().handleDelivery("control-tag", new Envelope(42L, false, "control.exchange", "control.route"),
                new AMQP.BasicProperties.Builder().contentType(RabbitMessage.TEXT).contentEncoding("UTF-8").build(),
                "zażółć".getBytes(StandardCharsets.UTF_8));
            if (outcome.equals("ACK")) verify(channel, timeout(2000)).basicAck(eq(42L), anyBoolean());
            else verify(channel, timeout(2000)).basicNack(eq(42L), anyBoolean(), eq(outcome.equals("REQUEUE")));
            assertThat(delivered.get().text()).isEqualTo("zażółć");
            assertThat(delivered.get().receivedRoutingKey()).isEqualTo("control.route");
            verify(channel, never()).basicQos(29, false);
        } finally {
            listeners.stop("control");
            registry.destroy();
            listeners.destroy();
        }
    }
}
