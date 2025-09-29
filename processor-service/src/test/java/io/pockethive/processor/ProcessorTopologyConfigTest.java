package io.pockethive.processor;

import io.pockethive.Topology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorTopologyConfigTest {

    private final ProcessorTopologyConfig config = new ProcessorTopologyConfig();

    @Test
    void moderatedQueueIsDurableAndBoundToTrafficExchange() {
        TopicExchange exchange = config.trafficExchange();
        Queue queue = config.moderatedQueue();
        Binding binding = config.moderatedBinding(queue, exchange);

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
