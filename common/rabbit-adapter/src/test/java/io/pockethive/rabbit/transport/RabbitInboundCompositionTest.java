package io.pockethive.rabbit.transport;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import io.pockethive.rabbit.api.RabbitListenerBinding;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitListenerState;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.config.RabbitConnectionConfiguration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitInboundCompositionTest {
    @Test
    void startupRegistersPublicBindingAndDeliversThroughItsConfiguredListener() throws Exception {
        var channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.queueDeclarePassive("selected.control")).thenReturn(new AMQP.Queue.DeclareOk.Builder().queue("selected.control").build());
        var consumer = new AtomicReference<Consumer>();
        doAnswer(call -> {
            Consumer handler = call.getArgument(6);
            consumer.set(handler);
            handler.handleConsumeOk("tag");
            return "tag";
        }).when(channel).basicConsume(eq("selected.control"), eq(false), anyString(), eq(false), eq(false), anyMap(), any(Consumer.class));
        doAnswer(call -> { consumer.get().handleCancelOk("tag"); return null; }).when(channel).basicCancel("tag");
        var connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        when(connection.createChannel(false)).thenReturn(channel);
        var connections = mock(ConnectionFactory.class);
        when(connections.createConnection()).thenReturn(connection);
        var received = new AtomicReference<RabbitMessage>();
        new ApplicationContextRunner()
            .withUserConfiguration(io.pockethive.rabbit.config.RabbitWorkConnectionConfiguration.class)
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class,
                RabbitConnectionConfiguration.class, RabbitTransportAutoConfiguration.class))
            .withBean(ConnectionFactory.class, () -> connections)
            .withBean(RabbitListenerBinding.class, () -> new RabbitListenerBinding("cp", "selected.control", received::set, failure -> false))
            .withPropertyValues("pockethive.rabbit.work.host=work", "pockethive.rabbit.work.port=5673",
                "pockethive.rabbit.work.username=worker", "pockethive.rabbit.work.password=worksecret",
                "pockethive.rabbit.work.virtual-host=/work", "spring.rabbitmq.host=broker", "spring.rabbitmq.port=5672",
                "spring.rabbitmq.username=user", "spring.rabbitmq.password=secret", "spring.rabbitmq.virtual-host=/",
                "spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.listener.simple.prefetch=11")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var listeners = context.getBean(RabbitListeners.class);
                assertThat(listeners.state("cp")).isEqualTo(RabbitListenerState.STOPPED);
                verify(connections, never()).createConnection();
                listeners.start("cp");
                verify(channel, timeout(2000)).basicQos(11, false);
                verify(channel, timeout(2000)).basicConsume(eq("selected.control"), eq(false), anyString(), eq(false), eq(false), anyMap(), any(Consumer.class));
                consumer.get().handleDelivery("tag", new Envelope(9, false, "control", "signal.route"),
                    new AMQP.BasicProperties.Builder().contentType(RabbitMessage.TEXT).build(), new byte[]{65});
                verify(channel, timeout(2000)).basicAck(eq(9L), anyBoolean());
                assertThat(received.get().text()).isEqualTo("A");
                assertThat(received.get().receivedRoutingKey()).isEqualTo("signal.route");
                listeners.stop("cp");
            });
    }
}
