package io.pockethive.worker.sdk.input.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import io.pockethive.rabbit.transport.SpringRabbitListeners;
import io.pockethive.rabbit.work.RabbitInputProperties;
import io.pockethive.rabbit.work.RabbitWorkInputFactory;
import io.pockethive.work.api.WorkItemJsonCodec;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

class RabbitWorkAdmissionTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void realListenerAcksAdmissionAndRequeuesOnlyNotSubmittedDeliveryOnStop(int limit) throws Exception {
        var wire = mock(Channel.class);
        when(wire.isOpen()).thenReturn(true);
        when(wire.queueDeclarePassive("jobs")).thenReturn(new AMQP.Queue.DeclareOk.Builder().queue("jobs").build());
        var consumers = new ConcurrentHashMap<String, Consumer>();
        var registrations = new LinkedBlockingQueue<String>();
        var sequence = new AtomicInteger();
        doAnswer(call -> {
            String tag = "consumer-" + sequence.incrementAndGet();
            Consumer consumer = call.getArgument(6);
            consumers.put(tag, consumer);
            consumer.handleConsumeOk(tag);
            registrations.add(tag);
            return tag;
        }).when(wire).basicConsume(eq("jobs"), eq(false), anyString(), anyBoolean(), anyBoolean(), anyMap(), any(Consumer.class));
        doAnswer(call -> {
            String tag = call.getArgument(0);
            consumers.get(tag).handleCancelOk(tag);
            return null;
        }).when(wire).basicCancel(anyString());
        var connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        when(connection.createChannel(false)).thenReturn(wire);
        var connections = mock(ConnectionFactory.class);
        when(connections.createConnection()).thenReturn(connection);
        var registry = new RabbitListenerEndpointRegistry();
        var listeners = new SpringRabbitListeners(registry, mock(ConnectionFactory.class), () -> connections,
            mock(SimpleRabbitListenerContainerFactoryConfigurer.class));
        var properties = new RabbitInputProperties();
        properties.setQueue("jobs");
        properties.setPrefetch(1);
        properties.setConcurrentConsumers(1);
        var channel = new RabbitWorkInputFactory(listeners).create("worker", properties);
        try (var scenario = new WorkAdmissionScenario(channel, limit)) {
            String tag = registrations.poll(2, TimeUnit.SECONDS);
            assertThat(tag).isNotNull();
            var codec = new WorkItemJsonCodec();
            for (int i = 0; i < limit; i++) {
                consumers.get(tag).handleDelivery(tag, new Envelope(i + 1, false, "work", "jobs"),
                    new AMQP.BasicProperties(), codec.toJson(WorkAdmissionScenario.item("accepted-" + i)));
                verify(wire, timeout(2000)).basicAck(eq((long) i + 1), anyBoolean());
            }
            assertThat(scenario.failed.getCount()).isEqualTo(limit);
            consumers.get(tag).handleDelivery(tag, new Envelope(limit + 1, false, "work", "jobs"),
                new AMQP.BasicProperties(), codec.toJson(WorkAdmissionScenario.item("waiting")));
            scenario.pauseWhileCapacityIsFull();
            verify(wire, timeout(2000)).basicNack(eq((long) limit + 1), anyBoolean(), eq(true));
            verify(wire, never()).basicAck(eq((long) limit + 1), anyBoolean());
            scenario.finishAcceptedAndResume();
            String resumed = registrations.poll(2, TimeUnit.SECONDS);
            assertThat(resumed).isNotNull();
            consumers.get(resumed).handleDelivery(resumed, new Envelope(limit + 2, true, "work", "jobs"),
                new AMQP.BasicProperties(), codec.toJson(WorkAdmissionScenario.item("waiting")));
            scenario.assertExecutedExactlyOnce();
            verify(wire, timeout(2000)).basicAck(eq((long) limit + 2), anyBoolean());
            verify(wire, times(1)).basicNack(anyLong(), anyBoolean(), anyBoolean());
        } finally { registry.destroy(); listeners.destroy(); }
    }
}
