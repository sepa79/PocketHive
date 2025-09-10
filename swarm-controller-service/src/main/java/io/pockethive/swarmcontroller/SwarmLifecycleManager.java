package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import io.pockethive.observability.StatusEnvelopeBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.pockethive.swarmcontroller.infra.docker.DockerContainerClient;

@Component
public class SwarmLifecycleManager implements SwarmLifecycle {
  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);
  private final AmqpAdmin amqp;
  private final ObjectMapper mapper;
  private final DockerContainerClient docker;
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final Map<String, List<String>> containers = new HashMap<>();
  private final Set<String> declaredQueues = new HashSet<>();
  private SwarmStatus status = SwarmStatus.STOPPED;
  private String template;

  public SwarmLifecycleManager(AmqpAdmin amqp,
                               ObjectMapper mapper,
                               DockerContainerClient docker,
                               RabbitTemplate rabbit,
                               @Qualifier("instanceId") String instanceId) {
    this.amqp = amqp;
    this.mapper = mapper;
    this.docker = docker;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
  }

  @Override
  public void start(String planJson) {
    MDC.put("swarm_id", Topology.SWARM_ID);
    MDC.put("service", "swarm-controller");
    MDC.put("instance", instanceId);
    log.info("Starting swarm {}", Topology.SWARM_ID);
    try {
      if (containers.isEmpty()) {
        prepare(planJson);
      } else if (template == null) {
        template = planJson;
      }
      containers.values().forEach(ids -> ids.forEach(docker::startContainer));
      status = SwarmStatus.RUNNING;
    } finally {
      MDC.clear();
    }
  }

  @Override
  public void prepare(String templateJson) {
    MDC.put("swarm_id", Topology.SWARM_ID);
    MDC.put("service", "swarm-controller");
    MDC.put("instance", instanceId);
    log.info("Preparing swarm {}", Topology.SWARM_ID);
    try {
      this.template = templateJson;
      SwarmPlan plan = mapper.readValue(templateJson, SwarmPlan.class);
      TopicExchange hive = new TopicExchange("ph." + Topology.SWARM_ID + ".hive", true, false);
      amqp.declareExchange(hive);

      Set<String> suffixes = new HashSet<>();
      if (plan.bees() != null) {
        for (SwarmPlan.Bee bee : plan.bees()) {
          if (bee.work() != null) {
            if (bee.work().in() != null) suffixes.add(bee.work().in());
            if (bee.work().out() != null) suffixes.add(bee.work().out());
          }
          if (bee.image() != null) {
            String containerId = docker.createContainer(bee.image());
            containers.computeIfAbsent(bee.role(), r -> new ArrayList<>()).add(containerId);
          }
        }
      }

      for (String suffix : suffixes) {
        if (!declaredQueues.contains(suffix)) {
          Queue q = QueueBuilder.durable("ph." + Topology.SWARM_ID + "." + suffix).build();
          amqp.declareQueue(q);
          Binding b = BindingBuilder.bind(q).to(hive).with(suffix);
          amqp.declareBinding(b);
          declaredQueues.add(suffix);
        }
      }
    } catch (JsonProcessingException e) {
      log.warn("Invalid template payload", e);
    } finally {
      MDC.clear();
    }
  }

  @Override
  public void stop() {
    MDC.put("swarm_id", Topology.SWARM_ID);
    MDC.put("service", "swarm-controller");
    MDC.put("instance", instanceId);
    log.info("Stopping swarm {}", Topology.SWARM_ID);
    containers.values().forEach(ids -> ids.forEach(docker::stopAndRemoveContainer));
    containers.clear();

    for (String suffix : declaredQueues) {
      amqp.deleteQueue("ph." + Topology.SWARM_ID + "." + suffix);
    }
    amqp.deleteExchange("ph." + Topology.SWARM_ID + ".hive");
    declaredQueues.clear();

    String controlQueue = Topology.CONTROL_QUEUE + ".swarm-controller." + instanceId;
    String rk = "ev.status-delta.swarm-controller." + instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role("swarm-controller")
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update.swarm-controller",
            "sig.config-update.swarm-controller." + instanceId,
            "sig.status-request",
            "sig.status-request.swarm-controller",
            "sig.status-request.swarm-controller." + instanceId,
            "sig.swarm-start.*",
            "sig.swarm-stop.*")
        .controlOut(rk)
        .enabled(true)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
    status = SwarmStatus.STOPPED;
    MDC.clear();
  }

  @Override
  public SwarmStatus getStatus() {
    return status;
  }
}
