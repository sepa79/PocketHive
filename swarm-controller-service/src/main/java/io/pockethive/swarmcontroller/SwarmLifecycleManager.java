package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.util.BeeNameGenerator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
  private final Map<String, Long> lastSeen = new HashMap<>();
  private final Map<String, Boolean> enabled = new HashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<ScenarioTask> scheduledTasks = new ArrayList<>();
  private List<String> startOrder = List.of();
  private SwarmStatus status = SwarmStatus.STOPPED;
  private String template;

  private static final long STATUS_TTL_MS = 15_000L;

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
      setSwarmEnabled(true);
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
      startOrder = computeStartOrder(plan);

      Set<String> suffixes = new HashSet<>();
      if (plan.bees() != null) {
        for (Bee bee : plan.bees()) {
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
            String beeName = BeeNameGenerator.generate(bee.role(), Topology.SWARM_ID);
            String javaOpts = env.get("JAVA_TOOL_OPTIONS");
            if (javaOpts == null || javaOpts.isBlank()) {
              javaOpts = "";
            } else if (!javaOpts.endsWith(" ")) {
              javaOpts = javaOpts + " ";
            }
            env.put("JAVA_TOOL_OPTIONS", javaOpts + "-Dbee.name=" + beeName);
            log.info("creating container {} for role {} using image {}", beeName, bee.role(), bee.image());
            log.info("container env for {}: {}", beeName, env);
            String containerId = docker.createContainer(bee.image(), env, beeName);
            log.info("starting container {} ({}) for role {}", containerId, beeName, bee.role());
            docker.startContainer(containerId);
            containers.computeIfAbsent(bee.role(), r -> new ArrayList<>()).add(containerId);
          }
        }
      }

      for (String suffix : suffixes) {
        String queueName = "ph." + Topology.SWARM_ID + "." + suffix;
        boolean queueMissing = amqp.getQueueProperties(queueName) == null;
        if (queueMissing) {
          declaredQueues.remove(suffix);
        }
        if (queueMissing || !declaredQueues.contains(suffix)) {
          Queue q = QueueBuilder.durable(queueName).build();
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
    setSwarmEnabled(false);

    String controlQueue = Topology.CONTROL_QUEUE + ".swarm-controller." + instanceId;
    String rk = "ev.status-delta.swarm-controller." + instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role("swarm-controller")
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .controlIn(controlQueue)
        .controlRoutes(
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, null, null),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "swarm-controller", null),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "swarm-controller", instanceId),
            ControlPlaneRouting.signal("config-update", "ALL", "swarm-controller", null),
            "sig.status-request",
            "sig.status-request.swarm-controller",
            "sig.status-request.swarm-controller." + instanceId,
            "sig.swarm-template." + Topology.SWARM_ID,
            "sig.swarm-start." + Topology.SWARM_ID,
            "sig.swarm-stop." + Topology.SWARM_ID,
            "sig.swarm-remove." + Topology.SWARM_ID)
        .controlOut(rk)
        .enabled(true)
        .data("swarmStatus", status.name())
        .toJson();
    log.debug("[CTRL] SEND rk={} inst={} payload={}", rk, instanceId, snippet(payload));
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
    MDC.clear();
  }

  @Override
  public void remove() {
    MDC.put("swarm_id", Topology.SWARM_ID);
    MDC.put("service", "swarm-controller");
    MDC.put("instance", instanceId);
    log.info("Removing swarm {}", Topology.SWARM_ID);
    setSwarmEnabled(false);
    List<String> order = new ArrayList<>(startOrder);
    java.util.Collections.reverse(order);
    for (String role : order) {
      for (String id : containers.getOrDefault(role, List.of())) {
        log.info("stopping container {}", id);
        docker.stopAndRemoveContainer(id);
      }
    }
    containers.clear();

    for (String suffix : declaredQueues) {
      log.info("deleting queue ph.{}.{}", Topology.SWARM_ID, suffix);
      amqp.deleteQueue("ph." + Topology.SWARM_ID + "." + suffix);
    }
    amqp.deleteExchange("ph." + Topology.SWARM_ID + ".hive");
    declaredQueues.clear();

    status = SwarmStatus.REMOVED;
    MDC.clear();
  }

  @Override
  public SwarmStatus getStatus() {
    return status;
  }

  @Override
  public void updateHeartbeat(String role, String instance) {
    updateHeartbeat(role, instance, System.currentTimeMillis());
  }

  void updateHeartbeat(String role, String instance, long timestamp) {
    lastSeen.put(role + "." + instance, timestamp);
  }

  @Override
  public void updateEnabled(String role, String instance, boolean flag) {
    enabled.put(role + "." + instance, flag);
  }

  @Override
  public SwarmMetrics getMetrics() {
    int desired = expectedReady.values().stream().mapToInt(Integer::intValue).sum();
    long now = System.currentTimeMillis();
    int healthy = 0;
    int running = 0;
    int enabledCount = 0;
    long watermark = Long.MAX_VALUE;
    for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
      long ts = e.getValue();
      if (ts < watermark) watermark = ts;
      boolean isHealthy = now - ts <= STATUS_TTL_MS;
      if (isHealthy) healthy++;
      boolean en = enabled.getOrDefault(e.getKey(), false);
      if (en) {
        enabledCount++;
        if (isHealthy) running++;
      }
    }
    if (watermark == Long.MAX_VALUE) watermark = now;
    return new SwarmMetrics(desired, healthy, running, enabledCount, Instant.ofEpochMilli(watermark));
  }

  @Override
  public synchronized boolean markReady(String role, String instance) {
    instancesByRole.computeIfAbsent(role, r -> new ArrayList<>());
    if (!instancesByRole.get(role).contains(instance)) {
      instancesByRole.get(role).add(instance);
      log.info("bee {} of role {} marked ready", instance, role);
    }
    return isFullyReady();
  }

  private boolean isFullyReady() {
    long now = System.currentTimeMillis();
    for (Map.Entry<String, Integer> e : expectedReady.entrySet()) {
      List<String> ready = instancesByRole.getOrDefault(e.getKey(), List.of());
      if (ready.size() < e.getValue()) {
        return false;
      }
      for (String inst : ready) {
        Long ts = lastSeen.get(e.getKey() + "." + inst);
        if (ts == null || now - ts > STATUS_TTL_MS) {
          requestStatus(e.getKey(), inst);
          return false;
        }
      }
    }
    return !expectedReady.isEmpty();
  }

  private void requestStatus(String role, String instance) {
    String rk = "sig.status-request." + role + "." + instance;
    log.debug("[CTRL] SEND rk={} inst={} payload={}", rk, instanceId, "{}");
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, "{}");
  }

  private List<String> computeStartOrder(SwarmPlan plan) {
    List<String> roles = new ArrayList<>();
    if (plan.bees() == null) {
      return roles;
    }

    Map<String, Set<String>> producersByQueue = new HashMap<>();
    for (Bee bee : plan.bees()) {
      if (!roles.contains(bee.role())) {
        roles.add(bee.role());
      }
      if (bee.work() != null && bee.work().out() != null) {
        producersByQueue.computeIfAbsent(bee.work().out(), q -> new HashSet<>()).add(bee.role());
      }
    }

    Map<String, Set<String>> deps = new HashMap<>();
    Map<String, Set<String>> adj = new HashMap<>();
    for (String role : roles) {
      deps.put(role, new HashSet<>());
    }

    for (Bee bee : plan.bees()) {
      if (bee.work() != null && bee.work().in() != null) {
        Set<String> producers = producersByQueue.getOrDefault(bee.work().in(), Set.of());
        if (!producers.isEmpty()) {
          deps.get(bee.role()).addAll(producers);
          for (String p : producers) {
            adj.computeIfAbsent(p, k -> new HashSet<>()).add(bee.role());
          }
        }
      }
    }

    Map<String, Integer> indegree = new HashMap<>();
    for (String role : roles) {
      indegree.put(role, deps.get(role).size());
    }

    List<String> order = new ArrayList<>();
    ArrayDeque<String> q = new ArrayDeque<>();
    for (String role : roles) {
      if (indegree.get(role) == 0) {
        q.add(role);
      }
    }
    while (!q.isEmpty()) {
      String r = q.remove();
      order.add(r);
      for (String nxt : adj.getOrDefault(r, Set.of())) {
        int d = indegree.merge(nxt, -1, Integer::sum);
        if (d == 0) {
          q.add(nxt);
        }
      }
    }
    if (order.size() < roles.size()) {
      log.warn("dependency cycle detected among bees");
      for (String r : roles) {
        if (!order.contains(r)) {
          order.add(r);
        }
      }
    }
    return order;
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
      publishConfigUpdate(data, "scenario step");
      log.info("scenario step applied for swarm {}", Topology.SWARM_ID);
    } catch (Exception e) {
      log.warn("scenario step", e);
    }
  }

  @Override
  public synchronized void enableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", true);
    publishConfigUpdate(data, "enable");
    status = SwarmStatus.RUNNING;
    for (ScenarioTask t : scheduledTasks) {
      scheduler.schedule(() -> {
        log.info("dispatching scheduled {} body {}", t.routingKey, t.body);
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, t.routingKey, t.body);
      }, t.delayMs, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public synchronized void setSwarmEnabled(boolean enabledFlag) {
    if (enabledFlag) {
      enableAll();
    } else {
      disableAll();
      status = SwarmStatus.STOPPED;
    }
  }

  private synchronized void disableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", false);
    publishConfigUpdate(data, "disable");
  }

  private void publishConfigUpdate(com.fasterxml.jackson.databind.node.ObjectNode data, String context) {
    Map<String, Object> dataMap = mapper.convertValue(data, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("data", dataMap);
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();

    String rawCommandTarget = asTextValue(dataMap.remove("commandTarget"));
    String targetHint = asTextValue(dataMap.remove("target"));
    String scopeHint = asTextValue(dataMap.remove("scope"));
    String swarmHint = asTextValue(dataMap.remove("swarmId"));
    String roleHint = asTextValue(dataMap.remove("role"));
    String instanceHint = asTextValue(dataMap.remove("instance"));

    CommandTarget commandTarget = parseCommandTarget(rawCommandTarget, context);
    if (commandTarget == null) {
      commandTarget = commandTargetFromScope(scopeHint);
    }
    if (commandTarget == null) {
      commandTarget = commandTargetFromTarget(targetHint);
    }
    if (commandTarget == null) {
      commandTarget = CommandTarget.SWARM;
    }

    String swarmId = normaliseSwarmHint(swarmHint);
    String role = roleHint;
    String instance = instanceHint;

    TargetSpec legacySpec = parseTargetSpec(targetHint);
    if (commandTarget == CommandTarget.INSTANCE) {
      if ((role == null || role.isBlank()) && legacySpec != null) {
        role = legacySpec.role();
      }
      if ((instance == null || instance.isBlank()) && legacySpec != null) {
        instance = legacySpec.instance();
      }
      if (role == null || role.isBlank()) {
        role = "swarm-controller";
      }
      if (instance == null || instance.isBlank()) {
        instance = instanceId;
      }
    } else if (commandTarget == CommandTarget.ROLE) {
      if ((role == null || role.isBlank()) && legacySpec != null) {
        role = legacySpec.role();
      }
      if (role == null || role.isBlank()) {
        throw new IllegalArgumentException("commandTarget=role requires role field");
      }
      instance = null;
    } else if (commandTarget == CommandTarget.SWARM || commandTarget == CommandTarget.ALL) {
      role = null;
      instance = null;
    }

    if (swarmId == null) {
      swarmId = Topology.SWARM_ID;
    }

    ControlSignal signal = new ControlSignal(
        "config-update",
        correlationId,
        idempotencyKey,
        swarmId,
        role,
        instance,
        commandTarget,
        args);
    try {
      String payload = mapper.writeValueAsString(signal);
      String routingKey = ControlPlaneRouting.signal("config-update", swarmId, role, instance);
      log.info("{} config-update rk={} correlation={} payload {}", context, routingKey, correlationId, snippet(payload));
      rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize config-update signal", e);
    }
  }

  private CommandTarget parseCommandTarget(String value, String context) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return CommandTarget.from(value);
    } catch (IllegalArgumentException ex) {
      log.warn("Ignoring unknown commandTarget {} on {} config-update", value, context);
      return null;
    }
  }

  private CommandTarget commandTargetFromScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return null;
    }
    return switch (scope.trim().toLowerCase(Locale.ROOT)) {
      case "swarm" -> CommandTarget.SWARM;
      case "role" -> CommandTarget.ROLE;
      case "controller", "instance" -> CommandTarget.INSTANCE;
      case "all" -> CommandTarget.ALL;
      default -> null;
    };
  }

  private CommandTarget commandTargetFromTarget(String target) {
    if (target == null || target.isBlank()) {
      return null;
    }
    String trimmed = target.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (lower.contains(".") || lower.contains(":")) {
      return CommandTarget.INSTANCE;
    }
    return switch (lower) {
      case "all" -> CommandTarget.ALL;
      case "swarm" -> CommandTarget.SWARM;
      case "controller", "instance" -> CommandTarget.INSTANCE;
      case "role" -> CommandTarget.ROLE;
      default -> null;
    };
  }

  private TargetSpec parseTargetSpec(String target) {
    if (target == null || target.isBlank()) {
      return null;
    }
    String trimmed = target.trim();
    String[] parts;
    if (trimmed.contains(".")) {
      parts = trimmed.split("\\.", 2);
    } else if (trimmed.contains(":")) {
      parts = trimmed.split(":", 2);
    } else {
      return null;
    }
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      return null;
    }
    return new TargetSpec(parts[0], parts[1]);
  }

  private record TargetSpec(String role, String instance) {}

  private String asTextValue(Object value) {
    if (value instanceof String s) {
      String trimmed = s.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }
    return null;
  }

  private String normaliseSwarmHint(String swarmHint) {
    if (swarmHint == null) {
      return null;
    }
    String trimmed = swarmHint.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return "ALL".equalsIgnoreCase(trimmed) ? null : trimmed;
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }
}
