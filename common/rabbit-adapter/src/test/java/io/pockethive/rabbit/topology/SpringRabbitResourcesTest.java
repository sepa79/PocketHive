package io.pockethive.rabbit.topology;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SpringRabbitResourcesTest {
    private final RabbitAdmin admin = mock(RabbitAdmin.class);
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final Channel channel = mock(Channel.class);
    private final SpringRabbitResources resources = new SpringRabbitResources(admin);

    @BeforeEach void connection() {
        lenient().when(admin.getRabbitTemplate()).thenReturn(template);
        lenient().when(template.execute(any())).thenAnswer(call -> ((ChannelCallback<?>) call.getArgument(0)).doInRabbit(channel));
    }

    @Test void preservesExplicitQueueAndBindingParameters() {
        resources.declareQueue(new RabbitQueueSpec("custom.queue", false, true, true, Map.of("x-message-ttl", 12000L)));
        verify(admin).declareQueue(argThat((Queue queue) -> queue.getName().equals("custom.queue")
            && !queue.isDurable() && queue.isExclusive() && queue.isAutoDelete()
            && queue.getArguments().get("x-message-ttl").equals(12000L)));
        resources.bind(new RabbitBindingSpec("custom.queue", "custom.exchange", "custom.route", Map.of()));
        verify(admin).declareBinding(argThat((Binding binding) -> binding.getDestination().equals("custom.queue")
            && binding.getExchange().equals("custom.exchange") && binding.getRoutingKey().equals("custom.route")));
    }

    @Test void projectsBrokerCounts() throws Exception {
        when(channel.queueDeclarePassive("jobs")).thenReturn(new AMQP.Queue.DeclareOk.Builder()
            .queue("jobs").messageCount(23).consumerCount(4).build());
        var observed = resources.queue("jobs").orElseThrow();
        assertThat(observed.messages()).isEqualTo(23);
        assertThat(observed.consumers()).isEqualTo(4);
        assertThat(observed.oldestAgeSeconds()).isEmpty();
    }

    @Test void reportsAbsenceOnlyForBrokerNotFound() throws Exception {
        when(channel.queueDeclarePassive("missing")).thenThrow(notFound());
        assertThat(resources.queue("missing")).isEmpty();
        when(channel.queueDeclarePassive("offline")).thenThrow(new AmqpException("connection unavailable"));
        assertThatThrownBy(() -> resources.queue("offline")).isInstanceOf(AmqpException.class);
    }

    @Test void accessRefusedIsNotAbsence() throws Exception {
        var failure = new AmqpException("access refused", new ShutdownSignalException(false, false,
            new AMQP.Channel.Close.Builder().replyCode(403).replyText("ACCESS_REFUSED").classId(50).methodId(10).build(), null));
        when(channel.queueDeclarePassive("private")).thenThrow(failure);
        when(channel.exchangeDeclarePassive("private")).thenThrow(failure);
        assertThatThrownBy(() -> resources.queue("private")).isSameAs(failure);
        assertThatThrownBy(() -> resources.exchangeExists("private")).isSameAs(failure);
    }

    @Test void deletionRequiresObservedAbsenceEvenWhenAdminReportsSuccess() throws Exception {
        when(admin.deleteQueue("jobs")).thenReturn(true);
        when(channel.queueDeclarePassive("jobs")).thenReturn(new AMQP.Queue.DeclareOk.Builder()
            .queue("jobs").messageCount(0).consumerCount(0).build());
        assertThatThrownBy(() -> resources.deleteQueue("jobs")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("still exists");
        when(channel.queueDeclarePassive("jobs")).thenThrow(notFound());
        assertThatCode(() -> resources.deleteQueue("jobs")).doesNotThrowAnyException();
    }

    @Test void deletionDoesNotTurnConnectionFailureIntoSuccess() throws Exception {
        when(channel.queueDeclarePassive("jobs")).thenThrow(new AmqpException("connection unavailable"));
        assertThatThrownBy(() -> resources.deleteQueue("jobs")).isInstanceOf(AmqpException.class);
    }

    @Test void exchangeDeletionVerifiesAbsence() throws Exception {
        assertThatThrownBy(() -> resources.deleteExchange("work")).isInstanceOf(IllegalStateException.class);
        when(channel.exchangeDeclarePassive("work")).thenThrow(notFound());
        assertThatCode(() -> resources.deleteExchange("work")).doesNotThrowAnyException();
    }

    private static AmqpException notFound() {
        return new AmqpException("not found", new ShutdownSignalException(false, false,
            new AMQP.Channel.Close.Builder().replyCode(404).replyText("NOT_FOUND").classId(50).methodId(10).build(), null));
    }
}
