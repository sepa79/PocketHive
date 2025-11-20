package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.Work;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.PushgatewaySettings;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.WorkerSettings;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.guard.BufferGuardController;
import io.pockethive.swarmcontroller.guard.BufferGuardSettings;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
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
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.stereotype.Component;

import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.util.BeeNameGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Optional;

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
  private final RabbitProperties rabbitProperties;
  private final String role;
  private final String swarmId;
  private final String controlExchange;
  private final MeterRegistry meterRegistry;
  private final WorkerSettings workerSettings;
  private final Map<String, List<String>> containers = new HashMap<>();
  private final Set<String> declaredQueues = new HashSet<>();
  private final Map<String, Integer> expectedReady = new HashMap<>();
  private final Map<String, List<String>> instancesByRole = new HashMap<>();
  private final Map<String, Long> lastSeen = new HashMap<>();
  private final Map<String, Boolean> enabled = new HashMap<>();
  private final ConcurrentMap<String, AtomicLong> queueDepthValues = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Gauge> queueDepthGauges = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicInteger> queueConsumerValues = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Gauge> queueConsumerGauges = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AtomicLong> queueOldestValues = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Gauge> queueOldestGauges = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, PendingConfig> pendingConfigUpdates = new ConcurrentHashMap<>();
  private List<String> startOrder = List.of();
  private Optional<BufferGuardSettings> bufferGuardSettings = Optional.empty();
  private BufferGuardController bufferGuardController;
  private TrafficPolicy trafficPolicy;
  private SwarmStatus status = SwarmStatus.STOPPED;
  private boolean controllerEnabled = false;
  private String template;

  private static final long STATUS_TTL_MS = 15_000L;
  private static final long BOOTSTRAP_CONFIG_RESEND_DELAY_MS = 5_000L;
  private static final int DEFAULT_BUFFER_TARGET = 200;
  private static final int DEFAULT_BUFFER_MIN = 150;
  private static final int DEFAULT_BUFFER_MAX = 260;
  private static final Duration DEFAULT_SAMPLE_PERIOD = Duration.ofSeconds(5);
  private static final int DEFAULT_MOVING_AVERAGE_WINDOW = 4;
  private static final int DEFAULT_MAX_INCREASE_PCT = 10;
  private static final int DEFAULT_MAX_DECREASE_PCT = 15;
  private static final int DEFAULT_MIN_RATE = 1;
  private static final int DEFAULT_MAX_RATE = 50;
  private static final Duration DEFAULT_PREFILL_LOOKAHEAD = Duration.ofMinutes(2);
  private static final int DEFAULT_PREFILL_LIFT = 20;
  private static final int DEFAULT_BACKPRESSURE_HIGH = 500;
  private static final int DEFAULT_BACKPRESSURE_RECOVERY = 250;
  private static final int DEFAULT_BACKPRESSURE_MODERATOR = 15;

  @Autowired
  public SwarmLifecycleManager(AmqpAdmin amqp,
                               ObjectMapper mapper,
                               DockerContainerClient docker,
                               RabbitTemplate rabbit,
                               RabbitProperties rabbitProperties,
                               @Qualifier("instanceId") String instanceId,
                               SwarmControllerProperties properties,
                               MeterRegistry meterRegistry) {
    this(amqp, mapper, docker, rabbit, rabbitProperties, instanceId, properties, meterRegistry,
        deriveWorkerSettings(properties));
  }

  SwarmLifecycleManager(AmqpAdmin amqp,
                        ObjectMapper mapper,
                        DockerContainerClient docker,
                        RabbitTemplate rabbit,
                        RabbitProperties rabbitProperties,
                        String instanceId,
                        SwarmControllerProperties properties,
                        MeterRegistry meterRegistry,
                        WorkerSettings workerSettings) {
    this.amqp = amqp;
    this.mapper = mapper;
    this.docker = docker;
    this.rabbit = rabbit;
    this.rabbitProperties = Objects.requireNonNull(rabbitProperties, "rabbitProperties");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.role = properties.getRole();
    this.swarmId = properties.getSwarmId();
    this.controlExchange = properties.getControlExchange();
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.workerSettings = Objects.requireNonNull(workerSettings, "workerSettings");
  }

  private static WorkerSettings deriveWorkerSettings(SwarmControllerProperties properties) {
    Objects.requireNonNull(properties, "properties");
    SwarmControllerProperties.Traffic traffic = properties.getTraffic();
    SwarmControllerProperties.Pushgateway pushgateway = properties.getMetrics().pushgateway();
    PushgatewaySettings metrics = new PushgatewaySettings(
        pushgateway.enabled(),
        pushgateway.baseUrl(),
        pushgateway.pushRate(),
        pushgateway.shutdownOperation());
    return new WorkerSettings(
        properties.getSwarmId(),
        properties.getControlExchange(),
        properties.getControlQueuePrefixBase(),
        traffic.hiveExchange(),
        properties.getRabbit().logsExchange(),
        properties.getRabbit().logging().enabled(),
        metrics);
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
    setControllerEnabled(true);
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
      this.trafficPolicy = plan.trafficPolicy();
      Optional<BufferGuardSettings> resolvedGuard = resolveBufferGuard(plan);
      configureBufferGuard(resolvedGuard);
      TopicExchange hive = new TopicExchange(properties.hiveExchange(), true, false);
      amqp.declareExchange(hive);
      log.info("declared hive exchange {}", properties.hiveExchange());

      expectedReady.clear();
      instancesByRole.clear();
      pendingConfigUpdates.clear();
      startOrder = computeStartOrder(plan);

      List<Bee> bees = plan.bees() == null ? List.of() : plan.bees();
      Set<String> suffixes = new LinkedHashSet<>();
      List<Bee> runnableBees = new ArrayList<>();
      for (Bee bee : bees) {
        expectedReady.merge(bee.role(), 1, Integer::sum);
        Work work = bee.work();
        if (work != null) {
          if (hasText(work.in())) {
            suffixes.add(work.in());
          }
          if (hasText(work.out())) {
            suffixes.add(work.out());
          }
        }
        if (bee.image() != null) {
          runnableBees.add(bee);
        }
      }

      declareQueues(hive, suffixes);

      for (Bee bee : runnableBees) {
        String beeName = BeeNameGenerator.generate(bee.role(), swarmId);
        Map<String, String> env = new LinkedHashMap<>(
            ControlPlaneContainerEnvironmentFactory.workerEnvironment(beeName, bee.role(), workerSettings, rabbitProperties));
        applyWorkIoEnvironment(bee, env);
        String net = docker.resolveControlNetwork();
        if (hasText(net)) {
          env.put("CONTROL_NETWORK", net);
        }
        if (bee.env() != null) {
          env.putAll(bee.env());
        }
        log.info("creating container {} for role {} using image {}", beeName, bee.role(), bee.image());
        log.info("container env for {}: {}", beeName, env);
        String containerId = docker.createContainer(bee.image(), env, beeName);
        log.info("starting container {} ({}) for role {}", containerId, beeName, bee.role());
        docker.startContainer(containerId);
        containers.computeIfAbsent(bee.role(), r -> new ArrayList<>()).add(containerId);
        if (bee.config() != null && !bee.config().isEmpty()) {
          pendingConfigUpdates.put(beeName, new PendingConfig(bee.role(), Map.copyOf(bee.config())));
        }
      }
    } catch (JsonProcessingException e) {
      log.warn("Invalid template payload", e);
    }
  }

  private void declareQueues(TopicExchange hive, Set<String> suffixes) {
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
    setControllerEnabled(false);

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
    stopBufferGuard();
    setSwarmEnabled(false);
    trafficPolicy = null;
    List<String> order = new ArrayList<>(startOrder);
    java.util.Collections.reverse(order);
    for (String role : order) {
      for (String id : containers.getOrDefault(role, List.of())) {
        log.info("stopping container {}", id);
        docker.stopAndRemoveContainer(id);
      }
    }
    containers.clear();
    pendingConfigUpdates.clear();

    for (String suffix : declaredQueues) {
      String queueName = properties.queueName(suffix);
      log.info("deleting queue {}", queueName);
      amqp.deleteQueue(queueName);
      unregisterQueueMetrics(queueName);
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
    publishBootstrapConfigIfNecessary(role, instance);
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
        QueueStats stats = QueueStats.empty();
        snapshot.put(queueName, stats);
        updateQueueMetrics(queueName, stats);
        continue;
      }
      long depth = coerceLong(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
      int consumers = coerceInt(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));
      OptionalLong oldestAge = coerceOptionalLong(
          properties.get("x-queue-oldest-age-seconds"),
          properties.get("message_stats.age"),
          properties.get("oldest_message_age_seconds"));
      QueueStats stats = new QueueStats(depth, consumers, oldestAge);
      snapshot.put(queueName, stats);
      updateQueueMetrics(queueName, stats);
    }
    return snapshot;
  }

  private void updateQueueMetrics(String queueName, QueueStats stats) {
    AtomicLong depthValue = queueDepthValues.computeIfAbsent(queueName, this::registerDepthGauge);
    depthValue.set(stats.depth());

    AtomicInteger consumerValue =
        queueConsumerValues.computeIfAbsent(queueName, this::registerConsumerGauge);
    consumerValue.set(stats.consumers());

    long oldestValue = stats.oldestAgeSec().orElse(-1L);
    AtomicLong oldestGaugeValue =
        queueOldestValues.computeIfAbsent(queueName, this::registerOldestGauge);
    oldestGaugeValue.set(oldestValue);
  }

  private AtomicLong registerDepthGauge(String queueName) {
    AtomicLong holder = new AtomicLong();
    Gauge gauge = Gauge.builder("ph_swarm_queue_depth", holder, AtomicLong::doubleValue)
        .description("Depth of a PocketHive swarm queue")
        .tags(queueTags(queueName))
        .register(meterRegistry);
    queueDepthGauges.put(queueName, gauge);
    return holder;
  }

  private AtomicInteger registerConsumerGauge(String queueName) {
    AtomicInteger holder = new AtomicInteger();
    Gauge gauge = Gauge.builder("ph_swarm_queue_consumers", holder, AtomicInteger::doubleValue)
        .description("Active consumer count for a PocketHive swarm queue")
        .tags(queueTags(queueName))
        .register(meterRegistry);
    queueConsumerGauges.put(queueName, gauge);
    return holder;
  }

  private AtomicLong registerOldestGauge(String queueName) {
    AtomicLong holder = new AtomicLong(-1L);
    Gauge gauge = Gauge.builder("ph_swarm_queue_oldest_age_seconds", holder, AtomicLong::doubleValue)
        .description("Age in seconds of the oldest message visible on a PocketHive swarm queue")
        .tags(queueTags(queueName))
        .register(meterRegistry);
    queueOldestGauges.put(queueName, gauge);
    return holder;
  }

  private Tags queueTags(String queueName) {
    return Tags.of("swarm", swarmId, "queue", queueName);
  }

  private void unregisterQueueMetrics(String queueName) {
    AtomicLong depthValue = queueDepthValues.remove(queueName);
    if (depthValue != null) {
      Gauge gauge = queueDepthGauges.remove(queueName);
      if (gauge != null) {
        meterRegistry.remove(gauge);
      }
    }
    AtomicInteger consumerValue = queueConsumerValues.remove(queueName);
    if (consumerValue != null) {
      Gauge gauge = queueConsumerGauges.remove(queueName);
      if (gauge != null) {
        meterRegistry.remove(gauge);
      }
    }
    AtomicLong oldestValue = queueOldestValues.remove(queueName);
    if (oldestValue != null) {
      Gauge gauge = queueOldestGauges.remove(queueName);
      if (gauge != null) {
        meterRegistry.remove(gauge);
      }
    }
  }

  private void applyWorkIoEnvironment(Bee bee, Map<String, String> env) {
    Work work = bee.work();
    if (work == null) {
      return;
    }
    boolean hasInput = hasText(work.in());
    boolean hasOutput = hasText(work.out());
    if (hasInput) {
      env.put("POCKETHIVE_INPUT_RABBIT_QUEUE", properties.queueName(work.in()));
    }
    if (hasOutput) {
      env.put("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY", properties.queueName(work.out()));
    }
    if (hasInput || hasOutput) {
      env.put("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE", properties.hiveExchange());
    }
  }

  private void configureBufferGuard(Optional<BufferGuardSettings> resolvedGuard) {
    stopBufferGuard();
    bufferGuardSettings = resolvedGuard;
    resolvedGuard.ifPresent(this::startBufferGuard);
  }

  private void startBufferGuard(BufferGuardSettings settings) {
    bufferGuardController = new BufferGuardController(
        settings,
        amqp,
        meterRegistry,
        queueTags("buffer-guard"),
        rate -> sendBufferGuardRate(settings.targetRole(), rate));
    bufferGuardController.start();
    if (!controllerEnabled) {
      bufferGuardController.pause();
    }
    log.info("Buffer guard started for queue {} targeting role {} (target depth {})",
        settings.queueAlias(),
        settings.targetRole(),
        settings.targetDepth());
  }

  private void stopBufferGuard() {
    if (bufferGuardController == null && bufferGuardSettings.isEmpty()) {
      return;
    }
    if (bufferGuardController != null) {
      bufferGuardController.stop();
      bufferGuardController = null;
    }
    bufferGuardSettings = Optional.empty();
  }

  private void startBufferGuardIfConfigured() {
    if (!controllerEnabled) {
      return;
    }
    if (bufferGuardController != null) {
      bufferGuardController.resume();
      return;
    }
    bufferGuardSettings.ifPresent(this::startBufferGuard);
  }

  private void stopBufferGuardController() {
    if (bufferGuardController != null) {
      bufferGuardController.pause();
    }
  }

  private Optional<String> queueName(SwarmPlan plan, String role, Function<Work, String> extractor) {
    if (plan.bees() == null) {
      return Optional.empty();
    }
    return plan.bees().stream()
        .filter(bee -> role.equalsIgnoreCase(bee.role()))
        .map(Bee::work)
        .filter(Objects::nonNull)
        .map(extractor)
        .filter(this::hasText)
        .map(properties::queueName)
        .findFirst();
  }

  private void sendBufferGuardRate(String targetRole, double rate) {
    var data = mapper.createObjectNode();
    data.put("commandTarget", "ROLE");
    data.put("role", targetRole);
    data.put("ratePerSec", rate);
    publishConfigUpdate(data, "buffer-guard");
  }

  private Optional<BufferGuardSettings> resolveBufferGuard(SwarmPlan plan) {
    if (!properties.getFeatures().bufferGuardEnabled()) {
      TrafficPolicy policy = plan.trafficPolicy();
      if (policy != null && policy.bufferGuard() != null && Boolean.TRUE.equals(policy.bufferGuard().enabled())) {
        log.info("Buffer guard config supplied but feature disabled; ignoring for swarm {}", swarmId);
      }
      return Optional.empty();
    }
    TrafficPolicy policy = plan.trafficPolicy();
    if (policy == null) {
      return Optional.empty();
    }
    BufferGuardPolicy guard = policy.bufferGuard();
    if (guard == null || !Boolean.TRUE.equals(guard.enabled())) {
      return Optional.empty();
    }
    String queueAlias = guard.queueAlias();
    if (!hasText(queueAlias)) {
      log.warn("Buffer guard enabled but queueAlias missing; guard configuration ignored");
      return Optional.empty();
    }
    String queueName;
    try {
      queueName = properties.queueName(queueAlias);
    } catch (IllegalArgumentException ex) {
      log.warn("Buffer guard queue alias '{}' invalid: {}", queueAlias, ex.getMessage());
      return Optional.empty();
    }

    if (plan.bees() == null) {
      log.warn("Buffer guard requires at least one bee to determine target role");
      return Optional.empty();
    }
    String targetRole = plan.bees().stream()
        .filter(bee -> bee.work() != null && queueAlias.equalsIgnoreCase(bee.work().out()))
        .map(Bee::role)
        .findFirst()
        .orElse(null);
    if (!hasText(targetRole)) {
      log.warn("Buffer guard could not find a producer role for queue '{}'", queueAlias);
      return Optional.empty();
    }

    int targetDepth = defaultInt(guard.targetDepth(), DEFAULT_BUFFER_TARGET);
    int minDepth = defaultInt(guard.minDepth(), DEFAULT_BUFFER_MIN);
    int maxDepth = defaultInt(guard.maxDepth(), DEFAULT_BUFFER_MAX);
    Duration samplePeriod = parseDuration(guard.samplePeriod(), DEFAULT_SAMPLE_PERIOD);
    int movingAverageWindow = defaultInt(guard.movingAverageWindow(), DEFAULT_MOVING_AVERAGE_WINDOW);

    BufferGuardPolicy.Adjustment adjustPolicy = guard.adjust();
    BufferGuardSettings.Adjustment adjustment = new BufferGuardSettings.Adjustment(
        defaultInt(adjustPolicy != null ? adjustPolicy.maxIncreasePct() : null, DEFAULT_MAX_INCREASE_PCT),
        defaultInt(adjustPolicy != null ? adjustPolicy.maxDecreasePct() : null, DEFAULT_MAX_DECREASE_PCT),
        defaultInt(adjustPolicy != null ? adjustPolicy.minRatePerSec() : null, DEFAULT_MIN_RATE),
        defaultInt(adjustPolicy != null ? adjustPolicy.maxRatePerSec() : null, DEFAULT_MAX_RATE));

    BufferGuardPolicy.Prefill prefillPolicy = guard.prefill();
    boolean prefillEnabled = prefillPolicy != null && Boolean.TRUE.equals(prefillPolicy.enabled());
    Duration lookahead = parseDuration(prefillPolicy != null ? prefillPolicy.lookahead() : null, DEFAULT_PREFILL_LOOKAHEAD);
    int liftPct = Math.max(0, defaultInt(prefillPolicy != null ? prefillPolicy.liftPct() : null, DEFAULT_PREFILL_LIFT));
    BufferGuardSettings.Prefill prefill = new BufferGuardSettings.Prefill(prefillEnabled, lookahead, liftPct);

    BufferGuardPolicy.Backpressure bpPolicy = guard.backpressure();
    String downstreamAlias = bpPolicy != null ? bpPolicy.queueAlias() : null;
    String downstreamQueue = null;
    if (hasText(downstreamAlias)) {
      try {
        downstreamQueue = properties.queueName(downstreamAlias);
      } catch (IllegalArgumentException ex) {
        log.warn("Backpressure queue alias '{}' invalid: {}", downstreamAlias, ex.getMessage());
        downstreamQueue = null;
      }
    }
    int highDepth = defaultInt(bpPolicy != null ? bpPolicy.highDepth() : null, DEFAULT_BACKPRESSURE_HIGH);
    int recoveryDepth = defaultInt(bpPolicy != null ? bpPolicy.recoveryDepth() : null, DEFAULT_BACKPRESSURE_RECOVERY);
    if (recoveryDepth > highDepth) {
      recoveryDepth = highDepth;
    }
    BufferGuardSettings.Backpressure backpressure = new BufferGuardSettings.Backpressure(
        downstreamAlias,
        downstreamQueue,
        highDepth,
        recoveryDepth,
        defaultInt(bpPolicy != null ? bpPolicy.moderatorReductionPct() : null, DEFAULT_BACKPRESSURE_MODERATOR));

    double initialRate = clampRate(
        resolveInitialRate(plan, targetRole).orElse(adjustment.minRatePerSec()),
        adjustment);

    return Optional.of(new BufferGuardSettings(
        queueAlias,
        queueName,
        targetRole,
        clampRate(initialRate, adjustment),
        targetDepth,
        minDepth,
        maxDepth,
        samplePeriod,
        movingAverageWindow,
        adjustment,
        prefill,
        backpressure));
  }

  private double clampRate(double candidate, BufferGuardSettings.Adjustment adjustment) {
    double min = Math.max(0d, adjustment.minRatePerSec());
    double max = Math.max(min, adjustment.maxRatePerSec());
    if (!Double.isFinite(candidate)) {
      return min;
    }
    return Math.max(min, Math.min(max, candidate));
  }

  private int defaultInt(Integer candidate, int fallback) {
    return candidate != null ? candidate : fallback;
  }

  private Duration parseDuration(String candidate, Duration fallback) {
    if (!hasText(candidate)) {
      return fallback;
    }
    String text = candidate.trim().toLowerCase(Locale.ROOT);
    try {
      if (text.endsWith("ms")) {
        long amount = Long.parseLong(text.substring(0, text.length() - 2));
        return Duration.ofMillis(amount);
      }
      if (text.endsWith("s")) {
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return Duration.ofSeconds(amount);
      }
      if (text.endsWith("m")) {
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return Duration.ofMinutes(amount);
      }
      if (text.endsWith("h")) {
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return Duration.ofHours(amount);
      }
      return Duration.parse(text.toUpperCase(Locale.ROOT));
    } catch (Exception ex) {
      log.warn("Unable to parse duration '{}' ({}); using fallback {}", candidate, ex.getMessage(), fallback);
      return fallback;
    }
  }

  private OptionalDouble resolveInitialRate(SwarmPlan plan, String role) {
    if (plan.bees() == null) {
      return OptionalDouble.empty();
    }
    return plan.bees().stream()
        .filter(bee -> role.equalsIgnoreCase(bee.role()))
        .map(bee -> {
          OptionalDouble fromConfig = extractRatePerSec(bee.config());
          if (fromConfig.isPresent()) {
            return fromConfig;
          }
          return extractRatePerSec(bee.env());
        })
        .filter(OptionalDouble::isPresent)
        .mapToDouble(OptionalDouble::getAsDouble)
        .findFirst();
  }

  private OptionalDouble extractRatePerSec(Map<?, ?> source) {
    if (source == null || source.isEmpty()) {
      return OptionalDouble.empty();
    }
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      Object keyObj = entry.getKey();
      if (keyObj == null) {
        continue;
      }
      String normalized = keyObj.toString().trim().toLowerCase(Locale.ROOT);
      if (!normalized.endsWith("ratepersec")) {
        continue;
      }
      Object value = entry.getValue();
      if (value == null) {
        return OptionalDouble.empty();
      }
      if (value instanceof Number number) {
        return OptionalDouble.of(number.doubleValue());
      }
      try {
        return OptionalDouble.of(Double.parseDouble(value.toString()));
      } catch (NumberFormatException ignored) {
        return OptionalDouble.empty();
      }
    }
    return OptionalDouble.empty();
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
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
    acknowledgeBootstrapConfig(instance);
    return isFullyReady();
  }

  private void acknowledgeBootstrapConfig(String instance) {
    PendingConfig pending = pendingConfigUpdates.get(instance);
    if (pending != null) {
      pending.markAcknowledged();
    }
  }

  @Override
  public synchronized boolean isReadyForWork() {
    if (expectedReady.isEmpty()) {
      return true;
    }
    return isFullyReady();
  }

  @Override
  public TrafficPolicy trafficPolicy() {
    return trafficPolicy;
  }

  @Override
  public synchronized Optional<String> handleConfigUpdateError(String role, String instance, String error) {
    PendingConfig pending = pendingConfigUpdates.remove(instance);
    if (pending == null) {
      return Optional.empty();
    }
    String message = failureMessage(pending.role(), instance, error);
    log.warn(message);
    status = SwarmStatus.FAILED;
    return Optional.of(message);
  }

  @Override
  public synchronized void fail(String reason) {
    log.warn("Marking swarm {} failed: {}", swarmId, reason);
    status = SwarmStatus.FAILED;
  }

  @Override
  public boolean hasPendingConfigUpdates() {
    for (PendingConfig pending : pendingConfigUpdates.values()) {
      if (pending.awaitingAck()) {
        return true;
      }
    }
    return false;
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
        if (ts == null) {
          log.info("Requesting status for {}.{} because no heartbeat was recorded yet", e.getKey(), inst);
          requestStatus(e.getKey(), inst, "missing-heartbeat");
          return false;
        }
        long age = now - ts;
        if (age > STATUS_TTL_MS) {
          log.info(
              "Requesting status for {}.{} because heartbeat is stale (age={}ms, ttl={}ms)",
              e.getKey(),
              inst,
              age,
              STATUS_TTL_MS);
          requestStatus(e.getKey(), inst, "stale-heartbeat");
          return false;
        }
      }
    }
    return !expectedReady.isEmpty();
  }

  private void requestStatus(String role, String instance, String reason) {
    String rk = ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, role, instance);
    log.info("[CTRL] SEND rk={} inst={} payload={} (reason={})", rk, instanceId, "{}", reason);
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
   * Enable workloads by publishing a {@code config-update}.
   */
  @Override
  public synchronized void enableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", true);
    log.info("Issuing swarm-wide enable config-update for swarm {} (role={} instance={})",
        swarmId, role, instanceId);
    publishConfigUpdate(data, "enable");
    status = SwarmStatus.RUNNING;
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
      startBufferGuardIfConfigured();
    } else {
      disableAll();
      stopBufferGuardController();
      status = SwarmStatus.STOPPED;
    }
  }

  @Override
  public synchronized void setControllerEnabled(boolean enabled) {
    if (this.controllerEnabled == enabled) {
      return;
    }
    this.controllerEnabled = enabled;
    log.info("Swarm controller {} for swarm {} (role {})", enabled ? "enabled" : "disabled", swarmId, role);
    if (bufferGuardController != null) {
      if (enabled) {
        bufferGuardController.resume();
      } else {
        bufferGuardController.pause();
      }
    }
  }

  private synchronized void disableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", false);
    log.info("Issuing swarm-wide disable config-update for swarm {} (role={} instance={})",
        swarmId, role, instanceId);
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

  private void publishBootstrapConfigIfNecessary(String role, String instance) {
    publishBootstrapConfigIfNecessary(role, instance, false);
  }

  private void publishBootstrapConfigIfNecessary(String role, String instance, boolean force) {
    PendingConfig pending = pendingConfigUpdates.get(instance);
    if (pending == null) {
      return;
    }
    Map<String, Object> values = pending.values();
    if (values == null || values.isEmpty()) {
      pending.markAcknowledged();
      return;
    }
    long now = System.currentTimeMillis();
    if (!pending.shouldPublish(force, now)) {
      return;
    }
    com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
    var configNode = mapper.valueToTree(values);
    if (configNode instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode) {
      payload.setAll(objectNode);
    } else {
      payload.set("config", configNode);
    }
    payload.put("commandTarget", "INSTANCE");
    payload.put("role", pending.role());
    payload.put("instance", instance);
    log.info("Publishing bootstrap config for role={} instance={}{}", pending.role(), instance,
        force ? " (initial)" : " (retry)");
    publishConfigUpdate(payload, "bootstrap-config");
    pending.markPublished(now);
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

  private static final class PendingConfig {
    private final String role;
    private final Map<String, Object> values;
    private final AtomicBoolean awaitingAck = new AtomicBoolean(true);
    private volatile long lastPublishedAt = 0L;

    private PendingConfig(String role, Map<String, Object> values) {
      this.role = role;
      this.values = values;
    }

    String role() {
      return role;
    }

    Map<String, Object> values() {
      return values;
    }

    boolean awaitingAck() {
      return awaitingAck.get();
    }

    void markAcknowledged() {
      awaitingAck.set(false);
    }

    boolean shouldPublish(boolean force, long now) {
      if (!awaitingAck()) {
        return false;
      }
      if (force) {
        return true;
      }
      return now - lastPublishedAt >= BOOTSTRAP_CONFIG_RESEND_DELAY_MS;
    }

    void markPublished(long now) {
      lastPublishedAt = now;
    }
  }

  private String failureMessage(String role, String instance, String reason) {
    String base = "Config update failed for role=" + role + " instance=" + instance;
    if (reason == null || reason.isBlank()) {
      return base;
    }
    return base + ": " + reason;
  }

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
