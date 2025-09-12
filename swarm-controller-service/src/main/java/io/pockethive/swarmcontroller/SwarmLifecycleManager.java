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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.pockethive.docker.DockerContainerClient;

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
  private final Map<String, Integer> expectedReady = new HashMap<>();
  private final Map<String, List<String>> instancesByRole = new HashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<ScenarioTask> scheduledTasks = new ArrayList<>();
  private SwarmStatus status = SwarmStatus.STOPPED;
  private String template;

  private record ScenarioTask(long delayMs, String routingKey, String body) {}

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
      enableAll();
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
      log.info("declared hive exchange ph.{}.hive", Topology.SWARM_ID);

      expectedReady.clear();
      instancesByRole.clear();

      Set<String> suffixes = new HashSet<>();
      if (plan.bees() != null) {
        for (SwarmPlan.Bee bee : plan.bees()) {
          expectedReady.merge(bee.role(), 1, Integer::sum);
          if (bee.work() != null) {
            if (bee.work().in() != null) suffixes.add(bee.work().in());
            if (bee.work().out() != null) suffixes.add(bee.work().out());
          }
          if (bee.image() != null) {
            Map<String, String> env = new HashMap<>();
            env.put("PH_SWARM_ID", Topology.SWARM_ID);
            env.put("PH_CONTROL_EXCHANGE", Topology.CONTROL_EXCHANGE);
            env.put("RABBITMQ_HOST", java.util.Optional.ofNullable(System.getenv("RABBITMQ_HOST")).orElse("rabbitmq"));
            env.put("PH_LOGS_EXCHANGE", java.util.Optional.ofNullable(System.getenv("PH_LOGS_EXCHANGE")).orElse("ph.logs"));
            env.put("PH_ENABLED", "false");
            String net = docker.resolveControlNetwork();
            if (net != null && !net.isBlank()) {
              env.put("CONTROL_NETWORK", net);
            }
            if (bee.env() != null) {
              for (Map.Entry<String, String> e : bee.env().entrySet()) {
                String value = e.getValue();
                if (bee.work() != null) {
                  if (bee.work().in() != null)
                    value = value.replace("${in}", "ph." + Topology.SWARM_ID + "." + bee.work().in());
                  if (bee.work().out() != null)
                    value = value.replace("${out}", "ph." + Topology.SWARM_ID + "." + bee.work().out());
                }
                env.put(e.getKey(), value);
              }
            }
            log.info("creating container for role {} using image {}", bee.role(), bee.image());
            log.info("container env for {}: {}", bee.role(), env);
            String containerId = docker.createContainer(bee.image(), env);
            log.info("starting container {} for role {}", containerId, bee.role());
            docker.startContainer(containerId);
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
          log.info("declared queue ph.{}.{}", Topology.SWARM_ID, suffix);
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
    containers.values().forEach(ids -> ids.forEach(id -> {
      log.info("stopping container {}", id);
      docker.stopAndRemoveContainer(id);
    }));
    containers.clear();

    for (String suffix : declaredQueues) {
      log.info("deleting queue ph.{}.{}", Topology.SWARM_ID, suffix);
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
    log.info("sent stop status-delta for swarm {}", Topology.SWARM_ID);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
    status = SwarmStatus.STOPPED;
    MDC.clear();
  }

  @Override
  public SwarmStatus getStatus() {
    return status;
  }

  @Override
  public synchronized boolean markReady(String role, String instance) {
    instancesByRole.computeIfAbsent(role, r -> new ArrayList<>());
    if (!instancesByRole.get(role).contains(instance)) {
      instancesByRole.get(role).add(instance);
      log.info("bee {} of role {} marked ready", instance, role);
    }
    boolean ready = isFullyReady();
    if (ready) {
      log.info("swarm {} fully ready", Topology.SWARM_ID);
      rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, "ev.swarm-ready." + Topology.SWARM_ID, "");
    }
    return ready;
  }

  private boolean isFullyReady() {
    for (Map.Entry<String, Integer> e : expectedReady.entrySet()) {
      int count = instancesByRole.getOrDefault(e.getKey(), List.of()).size();
      if (count < e.getValue()) {
        return false;
      }
    }
    return !expectedReady.isEmpty();
  }

  @Override
  public synchronized void applyScenarioStep(String stepJson) {
    try {
      scheduledTasks.clear();
      var root = mapper.readTree(stepJson);
      var schedule = root.path("schedule");
      if (schedule.isArray()) {
        for (var n : schedule) {
          long delay = n.path("delayMs").asLong(0);
          String rk = n.path("routingKey").asText();
          String body = n.path("body").toString();
          scheduledTasks.add(new ScenarioTask(delay, rk, body));
        }
      }

      var config = root.path("config");
      var data = mapper.createObjectNode();
      if (config.isObject()) {
        data.setAll((com.fasterxml.jackson.databind.node.ObjectNode) config);
      }
      data.put("enabled", false);
      var wrapper = mapper.createObjectNode();
      wrapper.set("data", data);
      String payload = wrapper.toString();

      for (var entry : instancesByRole.entrySet()) {
        for (String inst : entry.getValue()) {
          String rk = "sig.config-update." + entry.getKey() + "." + inst;
          log.info("scenario step config-update {} payload {}", rk, payload);
          rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
        }
      }

      log.info("scenario step applied for swarm {}", Topology.SWARM_ID);
      rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, "ev.swarm-ready." + Topology.SWARM_ID, "");
    } catch (Exception e) {
      log.warn("scenario step", e);
    }
  }

  @Override
  public synchronized void enableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", true);
    var wrapper = mapper.createObjectNode();
    wrapper.set("data", data);
    String payload = wrapper.toString();

    for (var entry : instancesByRole.entrySet()) {
      for (String inst : entry.getValue()) {
        String rk = "sig.config-update." + entry.getKey() + "." + inst;
        log.info("enable config-update {} payload {}", rk, payload);
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
      }
    }
    status = SwarmStatus.RUNNING;
    for (ScenarioTask t : scheduledTasks) {
      scheduler.schedule(() -> {
        log.info("dispatching scheduled {} body {}", t.routingKey, t.body);
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, t.routingKey, t.body);
      }, t.delayMs, TimeUnit.MILLISECONDS);
    }
  }
}
