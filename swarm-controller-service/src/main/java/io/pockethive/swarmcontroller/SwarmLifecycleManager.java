package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.pockethive.docker.DockerContainerClient;

/**
 * Concrete {@link SwarmLifecycle} that wires the control plane to real infrastructure.
 * <p>
 * The manager accepts high-level commands from the orchestrator and turns them into RabbitMQ
 * exchanges/queues, Docker container lifecycles, and status updates. When walking through the code,
 * keep {@code docs/ARCHITECTURE.md#control-plane} open; each overridden method below maps to a control
 * signal or REST call and explains the container/queue work that happens under the hood.
 */
@Component
public class SwarmLifecycleManager implements SwarmLifecycle {
  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);

  private final AmqpAdmin amqp;
  private final ObjectMapper mapper;
  private final DockerContainerClient docker;
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final SwarmControllerProperties properties;
  private final String role;
  private final String swarmId;
  private final String controlExchange;
  private final PushgatewayConfig pushgatewayConfig;
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

  private void applyMetricsEnv(Map<String, String> env, String beeName) {
    if (!pushgatewayConfig.hasBaseUrl()) {
      return;
    }
    env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL", pushgatewayConfig.baseUrl());
    env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED", pushgatewayConfig.enabled());
    env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE", pushgatewayConfig.pushRate());
    env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION",
        pushgatewayConfig.shutdownOperation());
    env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB", swarmId);
    env.put("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_GROUPING_KEY_INSTANCE", beeName);
  }

  private record PushgatewayConfig(String baseUrl, String enabled, String pushRate, String shutdownOperation) {
    static PushgatewayConfig fromProperties(SwarmControllerProperties.Pushgateway pushgateway) {
      if (pushgateway == null) {
        return new PushgatewayConfig(null, "false", "PT1M", "DELETE");
      }
      String base = trimToNull(pushgateway.baseUrl());
      String enabled = Boolean.toString(pushgateway.enabled());
      String pushRate = pushgateway.pushRate() != null ? pushgateway.pushRate().toString() : null;
      String shutdown = trimToNull(pushgateway.shutdownOperation());
      return new PushgatewayConfig(base, enabled, pushRate, shutdown);
    }

    boolean hasBaseUrl() {
      return baseUrl != null && !baseUrl.isBlank();
    }

    private static String trimToNull(String value) {
      if (value == null) {
        return null;
      }
      String trimmed = value.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }
  }

  @Autowired
  public SwarmLifecycleManager(AmqpAdmin amqp,
                               ObjectMapper mapper,
                               DockerContainerClient docker,
                               RabbitTemplate rabbit,
                               @Qualifier("instanceId") String instanceId,
                               SwarmControllerProperties properties) {
    this(amqp, mapper, docker, rabbit, instanceId, properties,
        PushgatewayConfig.fromProperties(properties.getMetrics().pushgateway()));
  }

  SwarmLifecycleManager(AmqpAdmin amqp,
                        ObjectMapper mapper,
                        DockerContainerClient docker,
                        RabbitTemplate rabbit,
                        String instanceId,
                        SwarmControllerProperties properties,
                        PushgatewayConfig pushgatewayConfig) {
    this.amqp = amqp;
    this.mapper = mapper;
    this.docker = docker;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.properties = properties;
    this.role = properties.getRole();
    this.swarmId = properties.getSwarmId();
    this.controlExchange = properties.getControlExchange();
    this.pushgatewayConfig = pushgatewayConfig;
  }

  /**
   * Handle the orchestrator's request to start the swarm.
   * <p>
   * We resolve the active template (calling {@link #prepare(String)} if necessary) and finally flip the swarm
   * into an enabled state via {@link #setSwarmEnabled(boolean)}. When invoked after a pause, this path
   * simply re-emits the config update without re-creating infrastructure.
   */
  @Override
  public void start(String planJson) {
    log.info("Starting swarm {}", swarmId);
    if (containers.isEmpty()) {
      prepare(planJson);
    } else if (template == null) {
      template = planJson;
    }
    setSwarmEnabled(true);
  }

  /**
   * Materialise a swarm plan without enabling work.
   * <p>
   * This method reads the provided JSON into {@link SwarmPlan}, declares the hive exchange, and
   * ensures queues exist for every {@code work.in/out} suffix listed in the plan. For each
   * {@link Bee} that specifies a container image we generate an instance name (e.g.
   * {@code generator-demo-1}), inject PocketHive environment defaults (Rabbit host, log exchange,
   * {@code POCKETHIVE_CONTROL_PLANE_SWARM_ID}), and create/start the container via {@link DockerContainerClient}. The
   * computed start order is cached so {@link #remove()} can stop containers in reverse.
   * <p>
   * Junior maintainers can tweak the environment defaults above by editing the map before
   * {@link DockerContainerClient#createContainer} is invoked.
   */
  @Override
  public void prepare(String templateJson) {
    log.info("Preparing swarm {}", swarmId);
    try {
      this.template = templateJson;
      SwarmPlan plan = mapper.readValue(templateJson, SwarmPlan.class);
      TopicExchange hive = new TopicExchange(properties.hiveExchange(), true, false);
      amqp.declareExchange(hive);
      log.info("declared hive exchange {}", properties.hiveExchange());

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
            env.put("POCKETHIVE_CONTROL_PLANE_SWARM_ID", swarmId);
            env.put("POCKETHIVE_CONTROL_PLANE_EXCHANGE", controlExchange);
            env.put("RABBITMQ_HOST", properties.getRabbit().host());
            env.put("POCKETHIVE_LOGS_EXCHANGE", properties.getRabbit().logsExchange());
            String net = docker.resolveControlNetwork();
            if (net != null && !net.isBlank()) {
              env.put("CONTROL_NETWORK", net);
            }
            if (bee.env() != null) {
              for (Map.Entry<String, String> e : bee.env().entrySet()) {
                String value = e.getValue();
                if (bee.work() != null) {
                  if (bee.work().in() != null)
                    value = value.replace("${in}", properties.queueName(bee.work().in()));
                  if (bee.work().out() != null)
                    value = value.replace("${out}", properties.queueName(bee.work().out()));
                }
                env.put(e.getKey(), value);
              }
            }
            String beeName = BeeNameGenerator.generate(bee.role(), swarmId);
            env.put("POCKETHIVE_CONTROL_PLANE_INSTANCE_ID", beeName);
            applyMetricsEnv(env, beeName);
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
        String queueName = properties.queueName(suffix);
        boolean queueMissing = amqp.getQueueProperties(queueName) == null;
        if (queueMissing) {
          declaredQueues.remove(suffix);
        }
        Binding legacyBinding = new Binding(queueName, Binding.DestinationType.QUEUE,
            hive.getName(), suffix, null);
        amqp.removeBinding(legacyBinding);

        Queue queue = QueueBuilder.durable(queueName).build();
        if (queueMissing || !declaredQueues.contains(suffix)) {
          amqp.declareQueue(queue);
          log.info("declared queue {}", queueName);
        }

        Binding desiredBinding = BindingBuilder.bind(queue).to(hive).with(queueName);
        amqp.declareBinding(desiredBinding);
        declaredQueues.add(suffix);
      }
    } catch (JsonProcessingException e) {
      log.warn("Invalid template payload", e);
    }
  }

  /**
   * Stop workload processing while leaving infrastructure available for quick restarts.
   * <p>
   * We publish a {@code config-update} signal with {@code enabled=false} (via
   * {@link #setSwarmEnabled(boolean)}), emit a status-delta envelope for dashboards, and rely on the
   * orchestrator to observe the ready/error events described in {@code docs/ORCHESTRATOR-REST.md}.
   */
  @Override
  public void stop() {
    log.info("Stopping swarm {}", swarmId);
    setSwarmEnabled(false);

    String controlQueue = properties.controlQueueName(role, instanceId);
    String rk = "ev.status-delta." + role + "." + instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .origin(instanceId)
        .swarmId(swarmId)
        .controlIn(controlQueue)
        .controlRoutes(controllerControlRoutes())
        .controlOut(rk)
        .enabled(true)
        .data("swarmStatus", status.name())
        .toJson();
    log.debug("[CTRL] SEND rk={} inst={} payload={}", rk, instanceId, snippet(payload));
    rabbit.convertAndSend(controlExchange, rk, payload);
  }

  private String[] controllerControlRoutes() {
    String swarm = swarmId;
    return new String[] {
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", role, "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, role, "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, role, instanceId),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, "ALL", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", role, "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarm, role, "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarm, role, instanceId),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, swarm, role, "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, swarm, role, "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, swarm, role, "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, swarm, role, "ALL")
    };
  }

  /**
   * Fully tear down the swarm, deleting queues and removing Docker containers.
   * <p>
   * We iterate through the captured {@code startOrder} in reverse so downstream workers (e.g.
   * postprocessors) stop before their dependencies vanish. After clearing containers and queues we
   * update the internal {@link SwarmStatus} so {@link #getStatus()} and {@link #getMetrics()} reflect
   * the removal.
   */
  @Override
  public void remove() {
    log.info("Removing swarm {}", swarmId);
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
      String queueName = properties.queueName(suffix);
      log.info("deleting queue {}", queueName);
      amqp.deleteQueue(queueName);
    }
    amqp.deleteExchange(properties.hiveExchange());
    declaredQueues.clear();

    status = SwarmStatus.REMOVED;
  }

  /**
   * Expose the controller's current lifecycle state for REST callers.
   * <p>
   * Used by {@code GET /api/swarms/{id}} to render status badges. When debugging, pair this with
   * {@link #getMetrics()} to understand whether the swarm is paused, running, or removed.
   */
  @Override
  public SwarmStatus getStatus() {
    return status;
  }

  /**
   * Update heartbeat metadata whenever a worker pings the controller.
   * <p>
   * The default overload stores the current timestamp, which feeds into {@link #getMetrics()} and the
   * readiness check inside {@link #isFullyReady()}. During tests we call the timestamped overload to
   * simulate stale heartbeats.
   */
  @Override
  public void updateHeartbeat(String role, String instance) {
    updateHeartbeat(role, instance, System.currentTimeMillis());
  }

  void updateHeartbeat(String role, String instance, long timestamp) {
    lastSeen.put(role + "." + instance, timestamp);
  }

  /**
   * Remember whether a given worker accepted the latest config update.
   * <p>
   * Called when the control plane receives a {@code ready.config-update} event that includes the
   * worker's {@code enabled} flag. These markers drive the {@code running} count reported in
   * {@link #getMetrics()}.
   */
  @Override
  public void updateEnabled(String role, String instance, boolean flag) {
    enabled.put(role + "." + instance, flag);
  }

  /**
   * Summarise desired vs. healthy vs. enabled workers.
   * <p>
   * We calculate the totals from readiness expectations and heartbeats, then produce a
   * {@link SwarmMetrics} record that REST consumers use to populate health dashboards. The watermark
   * value represents the oldest heartbeat so on-call engineers can spot stragglers.
   */
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
  public Map<String, QueueStats> snapshotQueueStats() {
    List<String> suffixes;
    synchronized (declaredQueues) {
      suffixes = List.copyOf(declaredQueues);
    }
    Map<String, QueueStats> snapshot = new LinkedHashMap<>(suffixes.size());
    for (String suffix : suffixes) {
      String queueName = properties.queueName(suffix);
      Properties properties = amqp.getQueueProperties(queueName);
      if (properties == null) {
        snapshot.put(queueName, QueueStats.empty());
        continue;
      }
      long depth = coerceLong(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
      int consumers = coerceInt(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));
      OptionalLong oldestAge = coerceOptionalLong(
          properties.get("x-queue-oldest-age-seconds"),
          properties.get("message_stats.age"),
          properties.get("oldest_message_age_seconds"));
      snapshot.put(queueName, new QueueStats(depth, consumers, oldestAge));
    }
    return snapshot;
  }

  /**
   * Record that a worker reported ready and determine if the swarm can be marked live.
   * <p>
   * When the last expected worker role+instance combination arrives and the corresponding heartbeat is
   * fresh, this method returns {@code true}. Callers typically translate that into a
   * {@code ready.swarm-start} control event.
   */
  @Override
  public synchronized boolean markReady(String role, String instance) {
    instancesByRole.computeIfAbsent(role, r -> new ArrayList<>());
    if (!instancesByRole.get(role).contains(instance)) {
      instancesByRole.get(role).add(instance);
      log.info("bee {} of role {} marked ready", instance, role);
    }
    return isFullyReady();
  }

  @Override
  public synchronized boolean isReadyForWork() {
    if (expectedReady.isEmpty()) {
      return true;
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
    String rk = ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, role, instance);
    log.debug("[CTRL] SEND rk={} inst={} payload={}", rk, instanceId, "{}");
    rabbit.convertAndSend(controlExchange, rk, "{}");
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

  /**
   * Consume a scenario runner payload and pre-schedule control signals.
   * <p>
   * The payload may include a {@code schedule[]} array with future control messages (for example
   * {@code {"delayMs":1000,"routingKey":"sig.inject.demo","body":"{...}"}}). We store those in
   * {@link #scheduledTasks} and emit a disabled {@code config-update} so workers apply the configuration
   * without processing messages yet.
   */
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
      log.info("scenario step applied for swarm {}", swarmId);
    } catch (Exception e) {
      log.warn("scenario step", e);
    }
  }

  /**
   * Enable workloads and fire any delayed scenario commands.
   * <p>
   * After publishing a {@code config-update} with {@code enabled=true} we queue each
   * {@link ScenarioTask} with the scheduler so control injections (like traffic bursts) happen relative
   * to the enable moment.
   */
  @Override
  public synchronized void enableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", true);
    publishConfigUpdate(data, "enable");
    status = SwarmStatus.RUNNING;
    for (ScenarioTask t : scheduledTasks) {
      scheduler.schedule(() -> {
        log.info("dispatching scheduled {} body {}", t.routingKey, t.body);
        rabbit.convertAndSend(controlExchange, t.routingKey, t.body);
      }, t.delayMs, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Toggle the swarm into a running or paused state.
   * <p>
   * Control-plane REST endpoints call this helper after validating the desired boolean. Enabling
   * delegates to {@link #enableAll()} while disabling funnels through {@link #disableAll()} so both
   * paths reuse the same config-update publishing logic.
   */
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

    String resolvedSwarmId = normaliseSwarmHint(swarmHint);
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
        role = this.role;
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

    if (resolvedSwarmId == null) {
      resolvedSwarmId = swarmId;
    }

    ControlSignal signal = new ControlSignal(
        ControlPlaneSignals.CONFIG_UPDATE,
        correlationId,
        idempotencyKey,
        resolvedSwarmId,
        role,
        instance,
        instanceId,
        commandTarget,
        args);
    try {
      String payload = mapper.writeValueAsString(signal);
      String routingKey = ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, resolvedSwarmId, role, instance);
      log.info("{} config-update rk={} correlation={} payload {}", context, routingKey, correlationId, snippet(payload));
      rabbit.convertAndSend(controlExchange, routingKey, payload);
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

  private static long coerceLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string) {
      try {
        return Long.parseLong(string);
      } catch (NumberFormatException ignored) {
        return 0L;
      }
    }
    return 0L;
  }

  private static int coerceInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  private static OptionalLong coerceOptionalLong(Object... candidates) {
    if (candidates == null) {
      return OptionalLong.empty();
    }
    for (Object candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      if (candidate instanceof Number number) {
        return OptionalLong.of(number.longValue());
      }
      if (candidate instanceof String string) {
        try {
          return OptionalLong.of(Long.parseLong(string));
        } catch (NumberFormatException ignored) {
          // try next candidate
        }
      }
    }
    return OptionalLong.empty();
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
