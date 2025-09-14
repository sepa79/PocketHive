package io.pockethive.swarmcontroller;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitConfigTest {

  private final RabbitConfig config = new RabbitConfig();

  @Test
  void bindsStatusEventsWithPerComponentPattern() {
    Queue queue = config.controlQueue("inst1");
    TopicExchange exchange = config.controlExchange();
    Binding full = config.bindStatusFullEvents(queue, exchange);
    Binding delta = config.bindStatusDeltaEvents(queue, exchange);
    assertThat(full.getRoutingKey()).isEqualTo("ev.status-full.*.*");
    assertThat(delta.getRoutingKey()).isEqualTo("ev.status-delta.*.*");
  }
}
