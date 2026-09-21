package io.pockethive.rabbit.transport;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import io.pockethive.rabbit.api.RabbitListenerState;
import io.pockethive.rabbit.api.RabbitSubscription;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpringRabbitListenersTest {
    @ParameterizedTest
    @CsvSource({"17,1,true", "29,3,false"})
    void appliesSelectedSettingsToBrokerConsumersAndHonoursExplicitStart(int prefetch, int consumers, boolean exclusive) throws Exception {
        var channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.queueDeclarePassive("jobs")).thenReturn(new AMQP.Queue.DeclareOk.Builder().queue("jobs").build());
        var registered = new java.util.concurrent.ConcurrentHashMap<String, Consumer>();
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            Consumer consumer = call.getArgument(6);
            String tag = "tag" + sequence.incrementAndGet();
            registered.put(tag, consumer);
            consumer.handleConsumeOk(tag);
            return tag;
        }).when(channel).basicConsume(eq("jobs"), anyBoolean(), anyString(), anyBoolean(), eq(exclusive), anyMap(), any(Consumer.class));
        doAnswer(call -> {
            String tag = call.getArgument(0);
            registered.get(tag).handleCancelOk(tag);
            return null;
        }).when(channel).basicCancel(anyString());
        var connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        when(connection.createChannel(false)).thenReturn(channel);
        var connections = mock(ConnectionFactory.class);
        when(connections.createConnection()).thenReturn(connection);
        var configurer = mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        doAnswer(call -> {
            SimpleRabbitListenerContainerFactory factory = call.getArgument(0);
            factory.setConnectionFactory(connections);
            factory.setContainerCustomizer(container -> {
                container.setAutoDeclare(false);
                container.setReceiveTimeout(10L);
                container.setShutdownTimeout(1000L);
            });
            return null;
        }).when(configurer).configure(any(), eq(connections));
        var registry = new RabbitListenerEndpointRegistry();
        var control = mock(ConnectionFactory.class);
        var listeners = new SpringRabbitListeners(registry, control, () -> connections, configurer);
        var pending = new java.util.concurrent.LinkedBlockingQueue<java.util.concurrent.CompletableFuture<Void>>();
        try {
            listeners.register(new RabbitSubscription("worker", "jobs", prefetch, consumers, exclusive, false), message -> {
                var completion = new java.util.concurrent.CompletableFuture<Void>();
                pending.add(completion);
            });
            assertThat(listeners.state("worker")).isEqualTo(RabbitListenerState.STOPPED);
            verifyNoInteractions(channel);
            listeners.start("worker");
            verify(channel, timeout(2000).times(consumers)).basicQos(prefetch, false);
            verify(channel, timeout(2000).times(consumers)).basicConsume(eq("jobs"), eq(false), anyString(), eq(false), eq(exclusive), anyMap(), any(Consumer.class));
            assertThat(listeners.state("worker")).isEqualTo(RabbitListenerState.RUNNING);
            verifyNoInteractions(control, configurer);
            var consumer = registered.entrySet().iterator().next();
            var headers = new java.util.HashMap<String, Object>();
            headers.put("nullable", null);
            for (long delivery : new long[] {1L, 2L}) {
                consumer.getValue().handleDelivery(consumer.getKey(),
                    new com.rabbitmq.client.Envelope(delivery, false, "work", "jobs"),
                    new AMQP.BasicProperties.Builder().headers(headers).build(), new byte[] {1});
            }
            var first = pending.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            var second = pending.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            verify(channel, timeout(2000)).basicAck(eq(2L), anyBoolean());
            assertThat(first).isNotDone();
            assertThat(second).isNotDone();
            second.complete(null);
            first.completeExceptionally(new IllegalStateException("Processing failed after callback returned"));
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            listeners.stop("worker");
            assertThat(listeners.state("worker")).isEqualTo(RabbitListenerState.STOPPED);
        } finally {
            registry.destroy();
            listeners.destroy();
        }
    }
}
