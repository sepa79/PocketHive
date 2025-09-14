package io.pockethive.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitConfigTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void bindsReadyAndErrorWithWidePattern() {
        Queue queue = config.controlQueue("inst1");
        TopicExchange exchange = config.controlExchange();
        Binding ready = config.bindReady(queue, exchange);
        Binding error = config.bindError(queue, exchange);
        assertThat(ready.getRoutingKey()).isEqualTo("ev.ready.#");
        assertThat(error.getRoutingKey()).isEqualTo("ev.error.#");
    }
}
