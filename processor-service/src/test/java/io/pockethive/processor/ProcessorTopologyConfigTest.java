package io.pockethive.processor;

import io.pockethive.Topology;
import org.junit.jupiter.api.Test;
import java.util.Collection;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorTopologyConfigTest {

    private final ProcessorTopologyConfig config = new ProcessorTopologyConfig();

    @Test
    void moderatedQueueIsDurableAndBoundToTrafficExchange() {
        Declarables declarables = config.moderatedTrafficDeclarables();
        Collection<Declarable> values = declarables.getDeclarables();

        TopicExchange exchange = values.stream()
            .filter(TopicExchange.class::isInstance)
            .map(TopicExchange.class::cast)
            .findFirst()
            .orElseThrow();
        Queue queue = values.stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .findFirst()
            .orElseThrow();
        Binding binding = values.stream()
            .filter(Binding.class::isInstance)
            .map(Binding.class::cast)
            .findFirst()
            .orElseThrow();

        assertThat(queue.getName()).isEqualTo(Topology.MOD_QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(exchange.getName()).isEqualTo(Topology.EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(binding.getDestination()).isEqualTo(queue.getName());
        assertThat(binding.getDestinationType()).isEqualTo(DestinationType.QUEUE);
        assertThat(binding.getExchange()).isEqualTo(exchange.getName());
        assertThat(binding.getRoutingKey()).isEqualTo(Topology.MOD_QUEUE);
    }
}
