package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class SwarmLifecycleManager implements SwarmLifecycle {
  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);
  private final AmqpAdmin amqp;
  private final ObjectMapper mapper;

  public SwarmLifecycleManager(AmqpAdmin amqp, ObjectMapper mapper) {
    this.amqp = amqp;
    this.mapper = mapper;
  }

  @Override
  public void start(String planJson) {
    log.info("Starting swarm {}", Topology.SWARM_ID);
    try {
      SwarmPlan plan = mapper.readValue(planJson, SwarmPlan.class);
      TopicExchange hive = new TopicExchange("ph." + Topology.SWARM_ID + ".hive", true, false);
      amqp.declareExchange(hive);

      Set<String> suffixes = new HashSet<>();
      if (plan.bees() != null) {
        for (SwarmPlan.Bee bee : plan.bees()) {
          if (bee.work() != null) {
            if (bee.work().in() != null) suffixes.add(bee.work().in());
            if (bee.work().out() != null) suffixes.add(bee.work().out());
          }
        }
      }

      for (String suffix : suffixes) {
        Queue q = QueueBuilder.durable("ph." + Topology.SWARM_ID + "." + suffix).build();
        amqp.declareQueue(q);
        Binding b = BindingBuilder.bind(q).to(hive).with(suffix);
        amqp.declareBinding(b);
      }
    } catch (JsonProcessingException e) {
      log.warn("Invalid plan payload", e);
    }
  }

  @Override
  public void stop() {
    log.info("Stopping swarm {}", Topology.SWARM_ID);
  }
}
