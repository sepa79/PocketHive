package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.manager.ports.Clock;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.manager.runtime.ManagerLifecycle;
import io.pockethive.manager.runtime.ManagerRuntimeCore;
import io.pockethive.manager.runtime.ManagerStatus;
import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.manager.scenario.ScenarioContext;
import io.pockethive.manager.scenario.ScenarioEngine;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Topology;
import io.pockethive.swarm.model.TopologyEdge;
import io.pockethive.swarm.model.TopologyEndpoint;
import io.pockethive.swarm.model.TopologySelector;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarmcontroller.SwarmLifecycle;
import io.pockethive.swarmcontroller.SwarmMetrics;
import io.pockethive.swarmcontroller.SwarmReadinessTracker;
import io.pockethive.swarmcontroller.SwarmStatus;
import io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.swarmcontroller.infra.docker.WorkloadProvisioner;
import io.pockethive.swarmcontroller.QueueStats;
import io.pockethive.swarmcontroller.QueuePropertyCoercion;
import io.pockethive.swarmcontroller.SwarmLifecycleManager;
import io.pockethive.swarmcontroller.scenario.TimelineScenarioObserver;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.PushgatewaySettings;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.WorkerSettings;
import io.pockethive.util.BeeNameGenerator;
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
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

/**
 * Transport-agnostic core implementation of {@link SwarmLifecycle}.
 * <p>
 * This class coordinates plan preparation, container lifecycle, readiness tracking,
 * and guard execution using small infrastructure ports. It intentionally avoids
 * any Spring annotations so it can be reused by different Swarm Controller
 * runtimes or embedded tools.
 */
public final class SwarmRuntimeCore implements SwarmLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);
  private static final String SCENARIOS_RUNTIME_DESTINATION = "/app/scenarios-runtime";

  private final AmqpAdmin amqp;
  private final ObjectMapper mapper;
  private final DockerContainerClient docker;
  private final RabbitProperties rabbitProperties;
  private final SwarmControllerProperties properties;
  private final MeterRegistry meterRegistry;
  private final WorkerSettings workerSettings;
  private final ControlPlanePublisher controlPublisher;
  private final SwarmWorkTopologyManager topology;
  private final WorkloadProvisioner workloadProvisioner;
  private final ComputeAdapter computeAdapter;
  private final SwarmQueueMetrics queueMetrics;
  private final io.pockethive.manager.runtime.ConfigFanout configFanout;
  private final SwarmJournal journal;
  private final SwarmReadinessTracker readinessTracker;
  private final String instanceId;
  private final String role;
  private final String swarmId;
  private final String scenariosRuntimeRootSource;
  private final ManagerRuntimeCore managerCore;
  private final io.pockethive.swarmcontroller.scenario.TimelineScenario timelineScenario;
  private final ScenarioEngine scenarioEngine;
  private final java.time.Instant startedAt;

  private final Set<String> declaredQueues = new HashSet<>();
  private List<String> startOrder = List.of();
  private volatile SwarmRuntimeContext runtimeContext;
  private volatile SwarmRuntimeState runtimeState;
  private TrafficPolicy trafficPolicy;
  private SwarmStatus status = SwarmStatus.STOPPED;
  private boolean controllerEnabled = false;
  private String template;

  public SwarmRuntimeCore(AmqpAdmin amqp,
                          ObjectMapper mapper,
                          DockerContainerClient docker,
                          RabbitProperties rabbitProperties,
                          SwarmControllerProperties properties,
                          MeterRegistry meterRegistry,
                          ControlPlanePublisher controlPublisher,
                          SwarmWorkTopologyManager topology,
                          WorkloadProvisioner workloadProvisioner,
                          ComputeAdapter computeAdapter,
                          SwarmQueueMetrics queueMetrics,
                          io.pockethive.manager.runtime.ConfigFanout configFanout,
                          SwarmJournal journal,
                          String instanceId) {
    this.amqp = Objects.requireNonNull(amqp, "amqp");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.docker = Objects.requireNonNull(docker, "docker");
    this.rabbitProperties = Objects.requireNonNull(rabbitProperties, "rabbitProperties");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.controlPublisher = Objects.requireNonNull(controlPublisher, "controlPublisher");
    this.topology = Objects.requireNonNull(topology, "topology");
    this.workloadProvisioner = Objects.requireNonNull(workloadProvisioner, "workloadProvisioner");
    this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
    this.queueMetrics = Objects.requireNonNull(queueMetrics, "queueMetrics");
    this.configFanout = Objects.requireNonNull(configFanout, "configFanout");
    this.journal = journal != null ? journal : SwarmJournal.noop();
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.role = properties.getRole();
    this.swarmId = properties.getSwarmId();
    this.scenariosRuntimeRootSource = System.getenv("POCKETHIVE_SCENARIOS_RUNTIME_ROOT");
    this.workerSettings = deriveWorkerSettings(properties);
    this.readinessTracker = new SwarmReadinessTracker(this::requestStatus);
    this.managerCore = new ManagerRuntimeCore(
        new SwarmWorkTopologyPortAdapter(topology),
        new DockerWorkloadPortAdapter(workloadProvisioner),
        new SwarmControlPlanePortAdapter(controlPublisher),
        new SwarmQueueStatsPortAdapter(amqp),
        new SwarmMetricsPortAdapter(queueMetrics),
        Clock.system(),
        this.swarmId,
        this.role,
        this.instanceId);

    // Scenario plans should drive swarm lifecycle through the same core that
    // REST /api/swarms/{id}/start|stop uses so that status, config fan-out and
    // diagnostics stay consistent. To keep the ScenarioEngine transport-agnostic
    // we wrap ManagerRuntimeCore with a ManagerLifecycle that delegates
    // everything except swarm-wide enable/disable to the core, and maps those
    // to SwarmRuntimeCore.setSwarmEnabled(..).
    ManagerLifecycle scenarioManager = new ManagerLifecycle() {
      @Override
      public void prepare(String planJson) {
        managerCore.prepare(planJson);
      }

      @Override
      public void start(String planJson) {
        managerCore.start(planJson);
      }

      @Override
      public void stop() {
        managerCore.stop();
      }

      @Override
      public void remove() {
        managerCore.remove();
      }

      @Override
      public ManagerStatus getStatus() {
        return managerCore.getStatus();
      }

      @Override
      public boolean markReady(String role, String instance) {
        return managerCore.markReady(role, instance);
      }

      @Override
      public void updateHeartbeat(String role, String instance) {
        managerCore.updateHeartbeat(role, instance);
      }

      @Override
      public void updateEnabled(String role, String instance, boolean enabled) {
        managerCore.updateEnabled(role, instance, enabled);
      }

      @Override
      public io.pockethive.manager.runtime.ManagerMetrics getMetrics() {
        return managerCore.getMetrics();
      }

      @Override
      public java.util.Map<String, io.pockethive.manager.runtime.QueueStats> snapshotQueueStats() {
        return managerCore.snapshotQueueStats();
      }

      @Override
      public void enableAll() {
        // Scenario "start" swarm step – drive the same swarm-wide enablement
        // path that REST start uses, including config-update fan-out and
        // SwarmStatus updates.
        setSwarmEnabled(true);
      }

      @Override
      public void setWorkEnabled(boolean enabled) {
        // Scenario "stop" swarm step – mirror swarm-wide disable semantics.
        setSwarmEnabled(enabled);
      }

      @Override
      public void setManagerEnabled(boolean enabled) {
        managerCore.setManagerEnabled(enabled);
      }

      @Override
      public boolean isReadyForWork() {
        return managerCore.isReadyForWork();
      }
    };

    java.util.function.Supplier<ManagerRuntimeView> viewSupplier =
        () -> new ManagerRuntimeView(
            managerCore.getStatus(),
            managerCore.getMetrics(),
            java.util.Collections.emptyMap());
    ScenarioContext scenarioContext = new ScenarioContext(swarmId, scenarioManager, configFanout);
    this.timelineScenario = new io.pockethive.swarmcontroller.scenario.TimelineScenario(
        "default",
        mapper,
        new JournalTimelineScenarioObserver());
    this.scenarioEngine = new ScenarioEngine(
        java.util.List.of(timelineScenario),
        viewSupplier,
        scenarioContext);
    this.startedAt = java.time.Instant.now();
  }

  private static WorkerSettings deriveWorkerSettings(SwarmControllerProperties properties) {
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

  @Override
  public void start(String planJson) {
    log.info("Starting swarm {}", swarmId);
    managerCore.start(planJson);
    if (runtimeState == null || runtimeState.containersByRole().isEmpty()) {
      prepare(planJson);
    } else if (template == null) {
      template = planJson;
    }
    setControllerEnabled(true);
    setSwarmEnabled(true);
  }

  @Override
  public void prepare(String templateJson) {
    log.info("Preparing swarm {}", swarmId);
    managerCore.prepare(templateJson);
    try {
      this.template = templateJson;
      SwarmPlan plan = mapper.readValue(templateJson, SwarmPlan.class);
      this.trafficPolicy = plan.trafficPolicy();
      TopicExchange workExchange = topology.declareWorkExchange();

      readinessTracker.reset();
      startOrder = computeStartOrder(plan);

      List<Bee> bees = plan.bees() == null ? List.of() : plan.bees();
      Set<String> suffixes = new LinkedHashSet<>();
      List<Bee> runnableBees = new ArrayList<>();
      for (Bee bee : bees) {
        readinessTracker.registerExpected(bee.role());
        Work work = bee.work();
        if (work != null) {
          addQueueSuffixes(suffixes, work.in());
          addQueueSuffixes(suffixes, work.out());
        }
        if (bee.image() != null) {
          runnableBees.add(bee);
        }
      }
      topology.declareWorkQueues(workExchange, suffixes, declaredQueues);

      runtimeContext = new SwarmRuntimeContext(plan, startOrder, suffixes);
      runtimeState = new SwarmRuntimeState(runtimeContext);

      java.util.List<io.pockethive.manager.runtime.WorkerSpec> workerSpecs = new java.util.ArrayList<>();
      SutEnvironment sutEnv = plan.sutEnvironment();
      Set<String> roles = new LinkedHashSet<>();
      for (Bee bee : runnableBees) {
        String beeName = BeeNameGenerator.generate(bee.role(), swarmId);
        roles.add(bee.role());
        Map<String, String> env = new LinkedHashMap<>(
            ControlPlaneContainerEnvironmentFactory.workerEnvironment(beeName, bee.role(), workerSettings, rabbitProperties));
        String runId = envValue("POCKETHIVE_JOURNAL_RUN_ID");
        if (hasText(runId)) {
          env.put("POCKETHIVE_JOURNAL_RUN_ID", runId);
        }
        String templateId = requireEnvValue("POCKETHIVE_TEMPLATE_ID");
        env.put("POCKETHIVE_TEMPLATE_ID", templateId);
        if (bee.image() != null && !bee.image().isBlank()) {
          env.put("POCKETHIVE_RUNTIME_IMAGE", bee.image());
        }
        String stackName = runtimeStackName();
        if (hasText(stackName)) {
          env.put("POCKETHIVE_RUNTIME_STACK_NAME", stackName);
        }
        applyWorkIoEnvironment(bee, env);
        String net = docker.resolveControlNetwork();
        if (hasText(net)) {
          env.put("CONTROL_NETWORK", net);
        }
        if (bee.env() != null) {
          env.putAll(bee.env());
        }
        Map<String, Object> effectiveConfig = enrichConfigWithSut(bee.config(), sutEnv);
        List<String> volumes = resolveVolumes(effectiveConfig);
        if (hasText(scenariosRuntimeRootSource)) {
          java.util.List<String> merged = new java.util.ArrayList<>(volumes.size() + 1);
          merged.add(scenariosRuntimeRootSource + ":" + SCENARIOS_RUNTIME_DESTINATION);
          merged.addAll(volumes);
          volumes = java.util.List.copyOf(merged);
        }
        workerSpecs.add(new io.pockethive.manager.runtime.WorkerSpec(
            beeName,
            bee.role(),
            bee.image(),
            Map.copyOf(env),
            volumes));
        runtimeState.registerWorker(bee.role(), beeName, beeName);
        if (effectiveConfig != null && !effectiveConfig.isEmpty()) {
          configFanout.registerBootstrapConfig(beeName, bee.role(), effectiveConfig);
        }
      }
      journal.append(localEntry(
          "worker",
          "INFO",
          "workers-planned",
          Map.of("workers", workerSpecs.size(), "roles", List.copyOf(roles)),
          mdcCorrelationId(),
          mdcIdempotencyKey()));
      computeAdapter.applyWorkers(swarmId, workerSpecs);
      journal.append(localEntry(
          "worker",
          "INFO",
          "workers-provisioned",
          Map.of("workers", workerSpecs.size()),
          mdcCorrelationId(),
          mdcIdempotencyKey()));
      status = SwarmStatus.READY;
    } catch (JsonProcessingException e) {
      log.warn("Invalid template payload", e);
      journal.append(localEntry(
          "plan",
          "ERROR",
          "template-invalid",
          Map.of("message", safeMessage(e)),
          mdcCorrelationId(),
          mdcIdempotencyKey()));
    }
  }

  public void applyScenarioPlan(String planJson) {
    if (planJson == null || planJson.isBlank()) {
      log.info("Clearing scenario plan for swarm {}", swarmId);
    } else {
      log.info("Applying scenario plan for swarm {} ({} bytes)", swarmId, planJson.length());
      if (log.isDebugEnabled()) {
        log.debug("Scenario plan payload for swarm {}: {}", swarmId, snippet(planJson));
      }
    }
    timelineScenario.applyPlan(planJson);
  }

  public void resetScenarioPlan() {
    timelineScenario.reset();
  }

  public void setScenarioRuns(Integer runs) {
    timelineScenario.setRunCount(runs);
  }

  @Override
  public void stop() {
    log.info("Stopping swarm {}", swarmId);
    managerCore.stop();
    setSwarmEnabled(false);
    setControllerEnabled(false);
    this.status = SwarmStatus.STOPPED;

    String controlQueue = properties.controlQueueName(role, instanceId);
    String rk = ControlPlaneRouting.event(
        "metric",
        "status-delta",
        ConfirmationScope.forInstance(swarmId, role, instanceId));
	    String payload = new StatusEnvelopeBuilder()
	        .type("status-delta")
	        .role(role)
	        .instance(instanceId)
	        .origin(instanceId)
	        .swarmId(swarmId)
	        .runtime(runtimeMeta())
	        .workPlaneEnabled(false)
	        .tpsEnabled(false)
	        .controlIn(controlQueue)
	        .controlRoutes(io.pockethive.swarmcontroller.SwarmControllerRoutes.controllerControlRoutes(swarmId, role, instanceId))
        .controlOut(rk)
        .enabled(false)
        .data("swarmStatus", status.name())
        .toJson();
    log.debug("[CTRL] SEND rk={} inst={} payload={}", rk, instanceId, snippet(payload));
    controlPublisher.publishEvent(new EventMessage(rk, payload));
  }

  @Override
  public void remove() {
    log.info("Removing swarm {}", swarmId);
    managerCore.remove();
    setSwarmEnabled(false);
    trafficPolicy = null;
    SwarmRuntimeContext ctx = runtimeContext;
    SwarmRuntimeState state = runtimeState;
    List<String> order = new ArrayList<>(ctx != null ? ctx.startOrder() : startOrder);
    java.util.Collections.reverse(order);
    if (state != null) {
      // Containers are managed via ComputeAdapter; delegate teardown there.
      computeAdapter.removeWorkers(swarmId);

      Map<String, List<String>> instancesByRole = state.instancesByRole();
      for (Map.Entry<String, List<String>> entry : instancesByRole.entrySet()) {
        String role = entry.getKey();
        for (String instanceId : entry.getValue()) {
          String controlQueue = properties.controlQueueName(role, instanceId);
          try {
            log.info("deleting control queue {}", controlQueue);
            amqp.deleteQueue(controlQueue);
          } catch (Exception ex) {
            log.warn("Failed to delete control queue {}: {}", controlQueue, ex.getMessage());
          }
        }
      }
    }

    Set<String> suffixes = ctx != null ? ctx.queueSuffixes() : new LinkedHashSet<>(declaredQueues);
    topology.deleteWorkQueues(suffixes, queueMetrics::unregister);
    topology.deleteWorkExchange();
    declaredQueues.clear();
    runtimeContext = null;
    runtimeState = null;

    status = SwarmStatus.REMOVED;
  }

  @Override
  public SwarmStatus getStatus() {
    return status;
  }

  @Override
  public void updateHeartbeat(String role, String instance) {
    updateHeartbeat(role, instance, System.currentTimeMillis());
  }

  public void updateHeartbeat(String role, String instance, long timestamp) {
    readinessTracker.recordHeartbeat(role, instance, timestamp);
    configFanout.publishBootstrapConfigIfNecessary(instance, false);
    managerCore.updateHeartbeat(role, instance);
    // Scenario plans, guards and other manager-side helpers must not run while
    // the controller is disabled. Only tick the scenario engine once the
    // controller has been started via the normal lifecycle (REST
    // swarm-start / startSwarm or equivalent).
    if (controllerEnabled) {
      scenarioEngine.tick();
    }
  }

  @Override
  public void recordStatusSnapshot(String role, String instance, long timestamp) {
    readinessTracker.recordStatusSnapshot(role, instance, timestamp);
  }

  @Override
  public boolean hasFreshWorkerStatusSnapshotsSince(long cutoffMillis) {
    return readinessTracker.hasFreshSnapshotsSince(cutoffMillis);
  }

  @Override
  public void updateEnabled(String role, String instance, boolean flag) {
    readinessTracker.recordEnabled(role, instance, flag);
    managerCore.updateEnabled(role, instance, flag);
  }

  @Override
  public SwarmMetrics getMetrics() {
    return readinessTracker.metrics();
  }

  /**
   * Snapshot of scenario progress for status reporting.
   */
  public io.pockethive.swarmcontroller.scenario.TimelineScenario.Progress timelineScenarioProgress() {
    return timelineScenario.snapshotProgress();
  }

  @Override
  public Map<String, QueueStats> snapshotQueueStats() {
    SwarmRuntimeContext ctx = runtimeContext;
    Set<String> suffixes = ctx != null ? ctx.queueSuffixes() : new LinkedHashSet<>(declaredQueues);
    Set<String> queueNames = new LinkedHashSet<>(suffixes.size());
    for (String suffix : suffixes) {
      queueNames.add(properties.queueName(suffix));
    }
    managerCore.setTrackedQueues(queueNames);
    Map<String, io.pockethive.manager.runtime.QueueStats> raw = managerCore.snapshotQueueStats();
    Map<String, QueueStats> converted = new LinkedHashMap<>(raw.size());
    for (Map.Entry<String, io.pockethive.manager.runtime.QueueStats> entry : raw.entrySet()) {
      io.pockethive.manager.runtime.QueueStats s = entry.getValue();
      converted.put(entry.getKey(), new QueueStats(s.depth(), s.consumers(), s.oldestAgeSeconds()));
    }
    return converted;
  }

  @Override
  public Map<String, Object> workBindingsSnapshot() {
    Map<String, Object> work = new LinkedHashMap<>();
    work.put("exchange", properties.hiveExchange());
    List<Map<String, Object>> edgesPayload = new ArrayList<>();
    work.put("edges", edgesPayload);

    SwarmRuntimeContext ctx = runtimeContext;
    SwarmPlan plan = ctx != null ? ctx.plan() : null;
    if (plan == null) {
      return Map.copyOf(work);
    }

    Topology topologyPlan = plan.topology();
    if (topologyPlan == null || topologyPlan.edges().isEmpty()) {
      return Map.copyOf(work);
    }

    List<Bee> bees = plan.bees() == null ? List.of() : plan.bees();
    Map<String, Bee> beesById = new HashMap<>();
    for (Bee bee : bees) {
      if (bee != null && hasText(bee.id())) {
        beesById.put(bee.id(), bee);
      }
    }
    Map<String, String> instanceByBeeId = mapInstancesByBeeId(bees, runtimeState);

    for (TopologyEdge edge : topologyPlan.edges()) {
      if (edge == null) {
        continue;
      }
      Bee fromBee = beesById.get(edge.from().beeId());
      Bee toBee = beesById.get(edge.to().beeId());
      if (fromBee == null || toBee == null) {
        continue;
      }
      Map<String, Object> edgePayload = new LinkedHashMap<>();
      edgePayload.put("edgeId", edge.id());
      edgePayload.put("from", bindingEndpointPayload(edge.from(), fromBee, instanceByBeeId, true));
      edgePayload.put("to", bindingEndpointPayload(edge.to(), toBee, instanceByBeeId, false));
      TopologySelector selector = edge.selector();
      if (selector != null) {
        Map<String, Object> selectorPayload = new LinkedHashMap<>();
        maybePut(selectorPayload, "policy", selector.policy());
        maybePut(selectorPayload, "expr", selector.expr());
        if (!selectorPayload.isEmpty()) {
          edgePayload.put("selector", selectorPayload);
        }
      }
      edgesPayload.add(edgePayload);
    }

    return Map.copyOf(work);
  }

  @Override
  public synchronized boolean markReady(String role, String instance) {
    configFanout.acknowledgeBootstrap(instance);
    return readinessTracker.markReady(role, instance);
  }

  @Override
  public synchronized boolean isReadyForWork() {
    return readinessTracker.isReadyForWork();
  }

  @Override
  public TrafficPolicy trafficPolicy() {
    return trafficPolicy;
  }

  @Override
  public synchronized Optional<String> handleConfigUpdateError(String role, String instance, String error) {
    Optional<String> message = configFanout.handleConfigUpdateError(instance, error);
    message.ifPresent(msg -> {
      log.warn(msg);
      status = SwarmStatus.FAILED;
    });
    return message;
  }

  @Override
  public synchronized void fail(String reason) {
    log.warn("Marking swarm {} failed: {}", swarmId, reason);
    status = SwarmStatus.FAILED;
  }

  @Override
  public boolean hasPendingConfigUpdates() {
    return configFanout.hasPendingAcks();
  }

  @Override
  public synchronized void enableAll() {
    managerCore.enableAll();
    var data = mapper.createObjectNode();
    data.put("enabled", true);
    log.info("Issuing swarm-wide enable config-update for swarm {} (role={} instance={})",
        swarmId, role, instanceId);
    configFanout.publishConfigUpdate(data, "enable");
    status = SwarmStatus.RUNNING;
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

  @Override
  public synchronized void setControllerEnabled(boolean enabled) {
    if (this.controllerEnabled == enabled) {
      return;
    }
    this.controllerEnabled = enabled;
    managerCore.setManagerEnabled(enabled);
    log.info("Swarm controller {} for swarm {} (role {})", enabled ? "enabled" : "disabled", swarmId, role);
  }

  private synchronized void disableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", false);
    log.info("Issuing swarm-wide disable config-update for swarm {} (role={} instance={})",
        swarmId, role, instanceId);
    configFanout.publishConfigUpdate(data, "disable");
  }

  private void requestStatus(String role, String instance, String reason) {
    String rk = ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, role, instance);
    String correlationId = mdcCorrelationId();
    if (!hasText(correlationId)) {
      correlationId = "status-request:" + java.util.UUID.randomUUID();
    }
    String idempotencyKey = mdcIdempotencyKey();
    if (!hasText(idempotencyKey)) {
      idempotencyKey = "status-request:" + java.util.UUID.randomUUID();
    }
	    io.pockethive.control.ControlScope target =
	        io.pockethive.control.ControlScope.forInstance(swarmId, role, instance);
	    io.pockethive.control.ControlSignal signal = io.pockethive.controlplane.messaging.ControlSignals.statusRequest(
	        instanceId,
	        target,
	        correlationId,
	        idempotencyKey,
	        runtimeMeta());
	    String payload = io.pockethive.observability.ControlPlaneJson.write(signal, "status-request signal");
	    log.info("[CTRL] SEND rk={} inst={} payload={} (reason={})", rk, instanceId, snippet(payload), reason);
	    controlPublisher.publishSignal(new io.pockethive.controlplane.messaging.SignalMessage(rk, payload));
	  }

	  private Map<String, Object> runtimeMeta() {
	    String templateId = requireEnvValue("POCKETHIVE_TEMPLATE_ID");
	    String runId = requireEnvValue("POCKETHIVE_JOURNAL_RUN_ID");
	    return Map.of("templateId", templateId, "runId", runId);
	  }

  private Map<String, String> mapInstancesByBeeId(List<Bee> bees, SwarmRuntimeState state) {
    if (bees == null || bees.isEmpty() || state == null) {
      return Map.of();
    }
    Map<String, List<String>> instancesByRole = state.instancesByRole();
    if (instancesByRole.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> roleOffsets = new HashMap<>();
    Map<String, String> mapping = new HashMap<>();
    for (Bee bee : bees) {
      if (bee == null) {
        continue;
      }
      String role = bee.role();
      int index = roleOffsets.getOrDefault(role, 0);
      List<String> instances = instancesByRole.get(role);
      if (instances != null && index < instances.size() && hasText(bee.id())) {
        mapping.put(bee.id(), instances.get(index));
      }
      roleOffsets.put(role, index + 1);
    }
    return mapping.isEmpty() ? Map.of() : Map.copyOf(mapping);
  }

  private Map<String, Object> bindingEndpointPayload(TopologyEndpoint endpoint,
                                                     Bee bee,
                                                     Map<String, String> instanceByBeeId,
                                                     boolean isFrom) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (endpoint == null || bee == null) {
      return payload;
    }
    maybePut(payload, "role", bee.role());
    if (instanceByBeeId != null && hasText(endpoint.beeId())) {
      maybePut(payload, "instance", instanceByBeeId.get(endpoint.beeId()));
    }
    maybePut(payload, "port", endpoint.port());
    Work work = bee.work();
    if (work != null) {
      Map<String, String> ports = isFrom ? work.out() : work.in();
      if (ports != null && !ports.isEmpty()) {
        String suffix = ports.get(endpoint.port());
        if (hasText(suffix)) {
          payload.put(isFrom ? "routingKey" : "queue", properties.queueName(suffix));
        }
      }
    }
    return payload;
  }

  private void maybePut(Map<String, Object> target, String key, String value) {
    if (target == null || key == null) {
      return;
    }
    if (hasText(value)) {
      target.put(key, value);
    }
  }

  private void applyWorkIoEnvironment(Bee bee, Map<String, String> env) {
    Work work = bee.work();
    if (work != null) {
      String inputQueue = work.defaultIn();
      String outputQueue = work.defaultOut();
      boolean hasInput = hasText(inputQueue);
      boolean hasOutput = hasText(outputQueue);
      if (hasInput) {
        env.put("POCKETHIVE_INPUT_RABBIT_QUEUE", properties.queueName(inputQueue));
      } else if (!work.in().isEmpty()) {
        log.warn("Bee {} declares input ports without a default; skipping input queue wiring", bee.role());
      }
      if (hasOutput) {
        env.put("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY", properties.queueName(outputQueue));
      } else if (!work.out().isEmpty()) {
        log.warn("Bee {} declares output ports without a default; skipping output queue wiring", bee.role());
      }
      if (hasInput || hasOutput) {
        env.put("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE", properties.hiveExchange());
      }
    }

    Map<String, Object> config = bee.config();
    if (config == null || config.isEmpty()) {
      return;
    }

    Object inputs = config.get("inputs");
    if (inputs instanceof Map<?, ?> inputsMap) {
      Object type = inputsMap.get("type");
      if (type != null) {
        String value = type.toString().trim();
        if (!value.isBlank()) {
          env.put("POCKETHIVE_INPUTS_TYPE", value.toUpperCase(Locale.ROOT));
        }
      }

      Object redis = inputsMap.get("redis");
      if (redis instanceof Map<?, ?> redisMap) {
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_HOST", redisMap.get("host"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_PORT", redisMap.get("port"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_USERNAME", redisMap.get("username"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_PASSWORD", redisMap.get("password"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_SSL", redisMap.get("ssl"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_LISTNAME", redisMap.get("listName"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_RATEPERSEC", redisMap.get("ratePerSec"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_INITIALDELAYMS", redisMap.get("initialDelayMs"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_TICKINTERVALMS", redisMap.get("tickIntervalMs"));
      }
    }

    Object outputs = config.get("outputs");
    if (outputs instanceof Map<?, ?> outputsMap) {
      Object type = outputsMap.get("type");
      if (type != null) {
        String value = type.toString().trim();
        if (!value.isBlank()) {
          env.put("POCKETHIVE_OUTPUTS_TYPE", value.toUpperCase(Locale.ROOT));
        }
      }
    }
  }

  private String runtimeStackName() {
    SwarmControllerProperties.Docker docker = properties.getDocker();
    ComputeAdapterType adapterType = docker == null
        ? ComputeAdapterType.DOCKER_SINGLE
        : ComputeAdapterType.defaulted(docker.computeAdapter());
    if (adapterType == ComputeAdapterType.SWARM_STACK) {
      return "ph-" + swarmId.toLowerCase(Locale.ROOT);
    }
    return null;
  }

  private static String envValue(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    String value = System.getenv(key);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private static String requireEnvValue(String key) {
    String value = envValue(key);
    if (value == null) {
      throw new IllegalStateException("Missing required environment variable: " + key);
    }
    return value;
  }

  private static void putEnvIfPresent(Map<String, String> env, String key, Object value) {
    if (value == null) {
      return;
    }
    String text = value.toString().trim();
    if (!text.isBlank()) {
      env.put(key, text);
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private void addQueueSuffixes(Set<String> target, Map<String, String> ports) {
    if (target == null || ports == null || ports.isEmpty()) {
      return;
    }
    for (String suffix : ports.values()) {
      if (hasText(suffix)) {
        target.add(suffix);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> resolveVolumes(Map<String, Object> config) {
    if (config == null || config.isEmpty()) {
      return List.of();
    }
    Object dockerObj = config.get("docker");
    if (!(dockerObj instanceof Map<?, ?> dockerMap) || dockerMap.isEmpty()) {
      return List.of();
    }
    Object volumesObj = dockerMap.get("volumes");
    if (!(volumesObj instanceof List<?> rawList) || rawList.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>(rawList.size());
    for (Object entry : rawList) {
      if (!(entry instanceof String s)) {
        continue;
      }
      String spec = s.trim();
      if (!spec.isBlank()) {
        result.add(spec);
      }
    }
    return result.isEmpty() ? List.of() : List.copyOf(result);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> enrichConfigWithSut(Map<String, Object> config,
                                                         SutEnvironment sutEnvironment) {
    if (sutEnvironment == null || config == null || config.isEmpty()) {
      return config == null || config.isEmpty() ? Map.of() : config;
    }

    Object sutObj = config.get("sut");
    if (!(sutObj instanceof Map<?, ?> sutMapRaw)) {
      return config;
    }
    Object endpointIdObj = sutMapRaw.get("targetEndpointId");
    if (!(endpointIdObj instanceof String endpointIdText)) {
      return config;
    }
    String endpointId = endpointIdText.trim();
    if (endpointId.isEmpty()) {
      return config;
    }
    SutEndpoint endpoint = sutEnvironment.endpoints().get(endpointId);
    if (endpoint == null) {
      return config;
    }

    Map<String, Object> newSut = new LinkedHashMap<>();
    sutMapRaw.forEach((key, value) -> {
      if (key != null) {
        newSut.put(key.toString(), value);
      }
    });
    newSut.put("environmentId", sutEnvironment.id());
    newSut.put("environmentType", sutEnvironment.type());
    newSut.put("environment", sutEnvironment);
    newSut.put("targetEndpointId", endpoint.id());
    newSut.put("targetEndpoint", endpoint);

    Map<String, Object> enriched = new LinkedHashMap<>(config);
    enriched.put("sut", Map.copyOf(newSut));

    // When SUT is explicitly configured, let its endpoint drive baseUrl for HTTP-style workers.
    String baseUrl = endpoint.baseUrl();
    if (baseUrl != null && !baseUrl.isBlank()) {
      enriched.put("baseUrl", baseUrl.trim());
    }

    return Map.copyOf(enriched);
  }

  private SwarmJournal.SwarmJournalEntry localEntry(String kind,
                                                    String severity,
                                                    String type,
                                                    Map<String, Object> data,
                                                    String correlationId,
                                                    String idempotencyKey) {
    return new SwarmJournal.SwarmJournalEntry(
        Instant.now(),
        swarmId,
        severity != null ? severity : "INFO",
        SwarmJournal.Direction.LOCAL,
        kind != null && !kind.isBlank() ? kind : "local",
        type,
        "swarm-controller",
        ControlScope.forInstance(swarmId, role, instanceId),
        correlationId,
        idempotencyKey,
        null,
        data,
        null,
        null);
  }

  private static String safeMessage(Throwable t) {
    if (t == null) {
      return null;
    }
    String msg = t.getMessage();
    if (msg == null || msg.isBlank()) {
      return t.getClass().getSimpleName();
    }
    return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
  }

  private static String mdcCorrelationId() {
    String value = MDC.get("correlation_id");
    return value != null && !value.isBlank() ? value : null;
  }

  private static String mdcIdempotencyKey() {
    String value = MDC.get("idempotency_key");
    return value != null && !value.isBlank() ? value : null;
  }

  private final class JournalTimelineScenarioObserver implements TimelineScenarioObserver {

    @Override
    public void onPlanCleared() {
      journal.append(localEntry("plan", "INFO", "scenario-plan-cleared", null, mdcCorrelationId(), mdcIdempotencyKey()));
    }

    @Override
    public void onPlanLoaded(int beeSteps, int swarmSteps) {
      journal.append(localEntry(
          "plan",
          "INFO",
          "scenario-plan-loaded",
          new LinkedHashMap<>(Map.of("beeSteps", beeSteps, "swarmSteps", swarmSteps)),
          mdcCorrelationId(),
          mdcIdempotencyKey()));
    }

    @Override
    public void onPlanParseFailed(String message) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("message", message != null ? message : "");
      journal.append(localEntry(
          "plan",
          "ERROR",
          "scenario-plan-parse-failed",
          data,
          mdcCorrelationId(),
          mdcIdempotencyKey()));
    }

    @Override
    public void onPlanReset() {
      journal.append(localEntry("plan", "INFO", "scenario-plan-reset", null, mdcCorrelationId(), mdcIdempotencyKey()));
    }

    @Override
    public void onTimelineStarted(Instant startedAt) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("startedAt", startedAt != null ? startedAt.toString() : null);
      journal.append(localEntry(
          "plan",
          "INFO",
          "scenario-timeline-started",
          data,
          null,
          null));
    }

    @Override
    public void onStepStarted(String stepId,
                              String name,
                              long dueMillis,
                              String type,
                              String role,
                              String instanceId,
                              boolean swarmLifecycleStep) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("stepId", stepId);
      data.put("name", name);
      data.put("dueMillis", dueMillis);
      data.put("stepType", type);
      data.put("targetRole", role);
      data.put("targetInstance", instanceId);
      data.put("swarmLifecycleStep", swarmLifecycleStep);
      journal.append(localEntry(
          "plan",
          "INFO",
          "scenario-step-started",
          data,
          null,
          null));
    }

    @Override
    public void onStepCompleted(String stepId,
                                String name,
                                long dueMillis,
                                String type,
                                String role,
                                String instanceId,
                                boolean swarmLifecycleStep) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("stepId", stepId);
      data.put("name", name);
      data.put("dueMillis", dueMillis);
      data.put("stepType", type);
      data.put("targetRole", role);
      data.put("targetInstance", instanceId);
      data.put("swarmLifecycleStep", swarmLifecycleStep);
      journal.append(localEntry(
          "plan",
          "INFO",
          "scenario-step-completed",
          data,
          null,
          null));
    }

    @Override
    public void onStepFailed(String stepId,
                             String name,
                             long dueMillis,
                             String type,
                             String role,
                             String instanceId,
                             boolean swarmLifecycleStep,
                             String message) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("stepId", stepId);
      data.put("name", name);
      data.put("dueMillis", dueMillis);
      data.put("stepType", type);
      data.put("targetRole", role);
      data.put("targetInstance", instanceId);
      data.put("swarmLifecycleStep", swarmLifecycleStep);
      data.put("message", message != null ? message : "");
      journal.append(localEntry(
          "plan",
          "ERROR",
          "scenario-step-failed",
          data,
          null,
          null));
    }

    @Override
    public void onRunCompleted(Integer totalRuns, Integer runsRemaining) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("totalRuns", totalRuns);
      data.put("runsRemaining", runsRemaining);
      journal.append(localEntry(
          "plan",
          "INFO",
          "scenario-run-completed",
          data,
          null,
          null));
    }

    @Override
    public void onPlanCompleted(Integer totalRuns, Integer runsRemaining) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("totalRuns", totalRuns);
      data.put("runsRemaining", runsRemaining);
      journal.append(localEntry(
          "plan",
          "INFO",
          "scenario-plan-completed",
          data,
          null,
          null));
    }
  }

  private List<String> computeStartOrder(SwarmPlan plan) {
    if (plan.bees() == null || plan.bees().isEmpty()) {
      return List.of();
    }
    List<String> roles = new ArrayList<>();
    Map<String, Set<String>> producersByQueue = new HashMap<>();
    for (Bee bee : plan.bees()) {
      if (!roles.contains(bee.role())) {
        roles.add(bee.role());
      }
      if (bee.work() != null && bee.work().out() != null) {
        for (String suffix : bee.work().out().values()) {
          if (hasText(suffix)) {
            producersByQueue.computeIfAbsent(suffix, q -> new HashSet<>()).add(bee.role());
          }
        }
      }
    }

    Map<String, Set<String>> deps = new HashMap<>();
    Map<String, Set<String>> adj = new HashMap<>();
    for (String role : roles) {
      deps.put(role, new HashSet<>());
    }

    for (Bee bee : plan.bees()) {
      if (bee.work() != null && bee.work().in() != null) {
        for (String suffix : bee.work().in().values()) {
          if (!hasText(suffix)) {
            continue;
          }
          Set<String> producers = producersByQueue.getOrDefault(suffix, Set.of());
          if (!producers.isEmpty()) {
            deps.get(bee.role()).addAll(producers);
            for (String p : producers) {
              adj.computeIfAbsent(p, k -> new HashSet<>()).add(bee.role());
            }
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
