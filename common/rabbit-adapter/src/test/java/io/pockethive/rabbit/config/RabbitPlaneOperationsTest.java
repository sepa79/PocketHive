package io.pockethive.rabbit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.Channel;
import io.pockethive.rabbit.api.*;
import io.pockethive.rabbit.topology.RabbitResourceAutoConfiguration;
import io.pockethive.rabbit.transport.RabbitTransportAutoConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RabbitPlaneOperationsTest {
    @Test
    void exportedDistinctSettingsDriveWorkOperationsWithoutTouchingControl() throws Exception {
        var controlChannel = mock(Channel.class);
        var workChannel = mock(Channel.class);
        when(controlChannel.isOpen()).thenReturn(true);
        when(workChannel.isOpen()).thenReturn(true);
        var declared = mock(com.rabbitmq.client.AMQP.Queue.DeclareOk.class);
        when(declared.getQueue()).thenReturn("jobs");
        when(workChannel.queueDeclare("jobs", true, false, false, Map.of())).thenReturn(declared);
        var controlConnection = mock(Connection.class);
        var workConnection = mock(Connection.class);
        when(controlConnection.createChannel(false)).thenReturn(controlChannel);
        when(workConnection.createChannel(false)).thenReturn(workChannel);
        var control = mock(ConnectionFactory.class);
        when(control.createConnection()).thenReturn(controlConnection);
        try (var factories = mockConstruction(CachingConnectionFactory.class, (factory, context) -> {
            var client = (com.rabbitmq.client.ConnectionFactory) context.arguments().getFirst();
            assertThat(client.getHost()).isEqualTo("work");
            assertThat(client.getPort()).isEqualTo(5673);
            assertThat(client.getUsername()).isEqualTo("work-user");
            assertThat(client.getPassword()).isEqualTo("work-secret");
            assertThat(client.getVirtualHost()).isEqualTo("/work");
            when(factory.createConnection()).thenReturn(workConnection);
        })) {
            new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class,
                    RabbitConnectionConfiguration.class, RabbitResourceAutoConfiguration.class,
                    RabbitTransportAutoConfiguration.class))
                .withBean(ConnectionFactory.class, () -> control)
                .withPropertyValues("spring.rabbitmq.host=control", "spring.rabbitmq.port=5672",
                    "spring.rabbitmq.username=control-user", "spring.rabbitmq.password=control-secret",
                    "spring.rabbitmq.virtual-host=/control", "pockethive.rabbit.work.host=work",
                    "pockethive.rabbit.work.port=5673", "pockethive.rabbit.work.username=work-user",
                    "pockethive.rabbit.work.password=work-secret", "pockethive.rabbit.work.virtual-host=/work")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(RabbitTransportBeans.WORK_PUBLISHER, RabbitPublisher.class).sendText("events", "route", "work");
                    context.getBean(RabbitResourceBeans.WORK, RabbitResources.class)
                        .declareQueue(new RabbitQueueSpec("jobs", true, false, false, Map.of()));
                    try {
                        verify(workChannel).basicPublish(eq("events"), eq("route"), eq(false), any(), eq("work".getBytes()));
                        verify(workChannel).queueDeclare("jobs", true, false, false, Map.of());
                        verifyNoInteractions(controlChannel);
                        context.getBean(RabbitTransportBeans.CONTROL_PUBLISHER, RabbitPublisher.class).sendText("events", "route", "control");
                        verify(controlChannel).basicPublish(eq("events"), eq("route"), eq(false), any(), eq("control".getBytes()));
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
        }
    }
}
