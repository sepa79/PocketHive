package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
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

  @Test
  void bindsLifecycleSignalsToCurrentSwarmOnly() {
    Queue queue = config.controlQueue("inst1");
    TopicExchange exchange = config.controlExchange();

    Binding template = config.bindSwarmTemplate(queue, exchange);
    Binding start = config.bindSwarmStart(queue, exchange);
    Binding stop = config.bindSwarmStop(queue, exchange);
    Binding remove = config.bindSwarmRemove(queue, exchange);

    assertThat(template.getRoutingKey()).isEqualTo("sig.swarm-template." + Topology.SWARM_ID);
    assertThat(start.getRoutingKey()).isEqualTo("sig.swarm-start." + Topology.SWARM_ID);
    assertThat(stop.getRoutingKey()).isEqualTo("sig.swarm-stop." + Topology.SWARM_ID);
    assertThat(remove.getRoutingKey()).isEqualTo("sig.swarm-remove." + Topology.SWARM_ID);

    String otherSwarm = Topology.SWARM_ID + "-other";
    assertThat(matches(template.getRoutingKey(), "sig.swarm-template." + otherSwarm)).isFalse();
    assertThat(matches(start.getRoutingKey(), "sig.swarm-start." + otherSwarm)).isFalse();
    assertThat(matches(stop.getRoutingKey(), "sig.swarm-stop." + otherSwarm)).isFalse();
    assertThat(matches(remove.getRoutingKey(), "sig.swarm-remove." + otherSwarm)).isFalse();

    assertThat(matches(start.getRoutingKey(), "sig.swarm-start." + Topology.SWARM_ID)).isTrue();
  }

  private static boolean matches(String pattern, String routingKey) {
    String[] patternTokens = pattern.split("\\.");
    String[] routingTokens = routingKey.split("\\.");
    return matches(patternTokens, 0, routingTokens, 0);
  }

  private static boolean matches(String[] patternTokens, int pIndex, String[] routingTokens, int rIndex) {
    if (pIndex == patternTokens.length) {
      return rIndex == routingTokens.length;
    }

    String token = patternTokens[pIndex];
    if ("#".equals(token)) {
      if (pIndex == patternTokens.length - 1) {
        return true;
      }
      for (int i = rIndex; i <= routingTokens.length; i++) {
        if (matches(patternTokens, pIndex + 1, routingTokens, i)) {
          return true;
        }
      }
      return false;
    }

    if (rIndex >= routingTokens.length) {
      return false;
    }

    if ("*".equals(token) || token.equals(routingTokens[rIndex])) {
      return matches(patternTokens, pIndex + 1, routingTokens, rIndex + 1);
    }

    return false;
  }
}
