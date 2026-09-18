package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.docker.DockerContainerClient;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.scenario.ManagerRuntimeView;
import io.pockethive.manager.scenario.ScenarioContext;
import io.pockethive.manager.scenario.ScenarioEngine;
import io.pockethive.manager.scenario.ScenarioLifecyclePort;
import io.pockethive.manager.runtime.QueueStats;
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
import io.pockethive.swarmcontroller.SwarmLifecycleCore;
import io.pockethive.swarmcontroller.SwarmMetrics;
import io.pockethive.swarmcontroller.SwarmReadinessTracker;
import io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.swarmcontroller.SwarmLifecycleManager;
import io.pockethive.swarmcontroller.scenario.TimelineScenarioObserver;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.MetricsSettings;
import io.pockethive.controlplane.spring.ControlPlaneContainerEnvironmentFactory.WorkerSettings;
import io.pockethive.sink.clickhouse.ClickHouseSinkProperties;
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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;

/**
 * Transport-agnostic core implementation of {@link SwarmLifecycle}.
 * <p>
 * This class coordinates plan preparation, container lifecycle, readiness tracking,
 * and guard execution using small infrastructure ports. It intentionally avoids
 * any Spring annotations so it can be reused by different Swarm Controller
 * runtimes or embedded tools.
 */
public final class SwarmRuntimeCore implements SwarmLifecycleCore {

  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);

  private final AmqpAdmin amqp;
  private final ObjectMapper mapper;
  private final DockerContainerClient docker;
  private final RabbitProperties rabbitProperties;
  private final SwarmControllerProperties properties;
  private final WorkerSettings workerSettings;
  private final ControlPlanePublisher controlPublisher;
  private final SwarmWorkTopologyManager topology;
  private final ComputeAdapter computeAdapter;
  private final SwarmQueueMetrics queueMetrics;
  private final io.pockethive.manager.runtime.ConfigFanout configFanout;
  private final SwarmJournal journal;
  private final SwarmReadinessTracker readinessTracker;
  private final String instanceId;
  private final String role;
  private final String swarmId;
  private final io.pockethive.controlplane.filesystem.RuntimeFilesystemMount runtimeFilesystemMount;
  private final ClickHouseSinkProperties clickHouseSink;
  private final io.pockethive.swarmcontroller.scenario.TimelineScenario timelineScenario;
  private final ScenarioEngine scenarioEngine;
  private final java.time.Instant startedAt;

  private final Set<String> declaredQueues = new HashSet<>();
  private List<String> startOrder = List.of();
  private volatile SwarmRuntimeContext runtimeContext;
  private volatile SwarmRuntimeState runtimeState;
  private TrafficPolicy trafficPolicy;
  private io.pockethive.swarm.model.lifecycle.WorkloadState workloadState =
      io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;
  private boolean controllerEnabled = false;
  private String template;

  public SwarmRuntimeCore(AmqpAdmin amqp,
                          ObjectMapper mapper,
                          DockerContainerClient docker,
                          RabbitProperties rabbitProperties,
                          SwarmControllerProperties properties,
                          ControlPlanePublisher controlPublisher,
                          SwarmWorkTopologyManager topology,
                          ComputeAdapter computeAdapter,
                          SwarmQueueMetrics queueMetrics,
                          io.pockethive.manager.runtime.ConfigFanout configFanout,
                          SwarmJournal journal,
                          String instanceId,
                          ClickHouseSinkProperties clickHouseSink,
                          io.pockethive.controlplane.filesystem.RuntimeFilesystemMount runtimeFilesystemMount) {
    this.amqp = Objects.requireNonNull(amqp, "amqp");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.docker = Objects.requireNonNull(docker, "docker");
    this.rabbitProperties = Objects.requireNonNull(rabbitProperties, "rabbitProperties");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.controlPublisher = Objects.requireNonNull(controlPublisher, "controlPublisher");
    this.topology = Objects.requireNonNull(topology, "topology");
    this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
    this.queueMetrics = Objects.requireNonNull(queueMetrics, "queueMetrics");
    this.configFanout = Objects.requireNonNull(configFanout, "configFanout");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.role = properties.getRole();
    this.swarmId = properties.getSwarmId();
    this.runtimeFilesystemMount = Objects.requireNonNull(runtimeFilesystemMount, "runtimeFilesystemMount");
    this.clickHouseSink = Objects.requireNonNull(clickHouseSink, "clickHouseSink");
    this.workerSettings = deriveWorkerSettings(properties);
    this.readinessTracker = new SwarmReadinessTracker(this::requestStatus);
    ScenarioLifecyclePort scenarioLifecycle = new ScenarioLifecyclePort() {
      @Override
      public void enableAll() {
        SwarmRuntimeCore.this.setSwarmEnabled(true);
      }

      @Override
      public void setWorkEnabled(boolean enabled) {
        SwarmRuntimeCore.this.setSwarmEnabled(enabled);
      }
    };

    ScenarioContext scenarioContext = new ScenarioContext(swarmId, scenarioLifecycle, configFanout);
    this.timelineScenario = new io.pockethive.swarmcontroller.scenario.TimelineScenario(
        "default",
        mapper,
        new JournalTimelineScenarioObserver());
    this.scenarioEngine = new ScenarioEngine(
        java.util.List.of(timelineScenario),
        this::scenarioRuntimeView,
        scenarioContext);
    this.startedAt = java.time.Instant.now();
  }

  private static WorkerSettings deriveWorkerSettings(SwarmControllerProperties properties) {
    SwarmControllerProperties.Traffic traffic = properties.getTraffic();
    SwarmControllerProperties.Metrics propertiesMetrics = properties.getMetrics();
    MetricsSettings metrics = new MetricsSettings(
        propertiesMetrics.adapter(),
        propertiesMetrics.publishInterval(),
        propertiesMetrics.clickHouse());
    return new WorkerSettings(
        properties.getSwarmId(),
        requireEnvValue("POCKETHIVE_JOURNAL_RUN_ID"),
        properties.getControlExchange(),
        properties.getControlQueuePrefixBase(),
        traffic.hiveExchange(),
        metrics);
  }

  @Override
  public void start(String planJson) {
    log.info("Starting swarm {}", swarmId);
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
        applyClickHouseSinkEnvironment(env);
        String net = docker.resolveControlNetwork();
        if (hasText(net)) {
          env.put("CONTROL_NETWORK", net);
        }
        if (bee.env() != null) {
          env.putAll(bee.env());
        }
        Map<String, Object> effectiveConfig = enrichConfigWithSut(bee.config(), sutEnv);
        List<String> volumes = resolveVolumes(effectiveConfig);
        java.util.List<String> merged = new java.util.ArrayList<>(volumes.size() + 1);
        merged.add(runtimeFilesystemMount.volume());
        merged.addAll(volumes);
        volumes = java.util.List.copyOf(merged);
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
      workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;
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
    setSwarmEnabled(false);
    setControllerEnabled(false);
    this.workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;

  }

  @Override
  public String sutId() {
    SwarmRuntimeContext ctx = runtimeContext;
    if (ctx == null) {
      return null;
    }
    SwarmPlan plan = ctx.plan();
    if (plan != null && plan.sutId() != null && !plan.sutId().isBlank()) {
      return plan.sutId().trim();
    }
    SutEnvironment sutEnvironment = ctx.sutEnvironment();
    if (sutEnvironment != null && sutEnvironment.id() != null && !sutEnvironment.id().isBlank()) {
      return sutEnvironment.id().trim();
    }
    return null;
  }

  @Override
  public List<io.pockethive.swarm.model.lifecycle.RemoveResource> remove() {
    log.info("Removing swarm {}", swarmId);
    List<io.pockethive.swarm.model.lifecycle.RemoveResource> removed = new ArrayList<>();
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
      instancesByRole.values().stream().flatMap(List::stream).forEach(workerId -> removed.add(
          new io.pockethive.swarm.model.lifecycle.RemoveResource(
              io.pockethive.swarm.model.lifecycle.RemoveResourceType.WORKER_RUNTIME, workerId)));
      for (Map.Entry<String, List<String>> entry : instancesByRole.entrySet()) {
        String role = entry.getKey();
        for (String instanceId : entry.getValue()) {
          String controlQueue = properties.controlQueueName(role, instanceId);
          log.info("deleting control queue {}", controlQueue);
          amqp.deleteQueue(controlQueue);
          removed.add(new io.pockethive.swarm.model.lifecycle.RemoveResource(
              io.pockethive.swarm.model.lifecycle.RemoveResourceType.RABBIT_QUEUE, controlQueue));
        }
      }
    }

    Set<String> suffixes = ctx != null ? ctx.queueSuffixes() : new LinkedHashSet<>(declaredQueues);
    topology.deleteWorkQueues(suffixes, queueMetrics::unregister);
    suffixes.stream().map(properties::queueName).forEach(queue -> removed.add(
        new io.pockethive.swarm.model.lifecycle.RemoveResource(
            io.pockethive.swarm.model.lifecycle.RemoveResourceType.RABBIT_QUEUE, queue)));
    topology.deleteWorkExchange();
    removed.add(new io.pockethive.swarm.model.lifecycle.RemoveResource(
        io.pockethive.swarm.model.lifecycle.RemoveResourceType.RABBIT_EXCHANGE, properties.hiveExchange()));
    declaredQueues.clear();
    runtimeContext = null;
    runtimeState = null;

    workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.UNAVAILABLE;
    return List.copyOf(removed);
  }

  @Override
  public io.pockethive.swarm.model.lifecycle.WorkloadState getWorkloadState() {
    return workloadState;
  }

  @Override
  public void updateHeartbeat(String role, String instance) {
    updateHeartbeat(role, instance, System.currentTimeMillis());
  }

  public void updateHeartbeat(String role, String instance, long timestamp) {
    readinessTracker.recordHeartbeat(role, instance, timestamp);
    configFanout.publishBootstrapConfigIfNecessary(instance, false);
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

  public List<io.pockethive.swarm.model.lifecycle.Target> nonConvergedWorkersSince(
      long cutoffMillis, boolean expectedEnabled) {
    return readinessTracker.nonConvergedWorkersSince(cutoffMillis, expectedEnabled);
  }

  @Override
  public void updateEnabled(String role, String instance, boolean flag) {
    readinessTracker.recordEnabled(role, instance, flag);
  }

  @Override
  public SwarmMetrics getMetrics() {
    return readinessTracker.metrics();
  }

  @Override
  public List<io.pockethive.swarm.model.lifecycle.Target> expectedWorkers() {
    SwarmRuntimeState state = runtimeState;
    if (state == null) {
      return List.of();
    }
    return state.instancesByRole().entrySet().stream()
        .flatMap(entry -> entry.getValue().stream()
            .map(instance -> new io.pockethive.swarm.model.lifecycle.Target(entry.getKey(), instance)))
        .sorted(java.util.Comparator.comparing(io.pockethive.swarm.model.lifecycle.Target::role)
            .thenComparing(io.pockethive.swarm.model.lifecycle.Target::instance))
        .toList();
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
    SwarmQueueStatsPortAdapter queueStats = new SwarmQueueStatsPortAdapter(amqp);
    Map<String, QueueStats> snapshot = new LinkedHashMap<>(queueNames.size());
    for (String queueName : queueNames) {
      QueueStats stats = queueStats.getQueueStats(queueName);
      snapshot.put(queueName, stats);
      queueMetrics.update(queueName, stats);
    }
    return Map.copyOf(snapshot);
  }

  ManagerRuntimeView scenarioRuntimeView() {
    SwarmMetrics metrics = getMetrics();
    return new ManagerRuntimeView(
        getWorkloadState(),
        new io.pockethive.manager.runtime.ManagerMetrics(
            metrics.desired(),
            metrics.healthy(),
            metrics.running(),
            metrics.enabled(),
            Objects.requireNonNull(metrics.watermark(), "metrics.watermark").toEpochMilli()),
        java.util.Collections.emptyMap());
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
    Map<String, Bee> beesByRole = beesByRole(bees);
    Map<String, String> instanceByRole = mapInstancesByRole(beesByRole.keySet(), runtimeState);

    for (TopologyEdge edge : topologyPlan.edges()) {
      if (edge == null) {
        continue;
      }
      Bee fromBee = beesByRole.get(edge.from().role());
      Bee toBee = beesByRole.get(edge.to().role());
      if (fromBee == null || toBee == null) {
        continue;
      }
      Map<String, Object> edgePayload = new LinkedHashMap<>();
      edgePayload.put("edgeId", edge.id());
      edgePayload.put("from", bindingEndpointPayload(edge.from(), fromBee, instanceByRole, true));
      edgePayload.put("to", bindingEndpointPayload(edge.to(), toBee, instanceByRole, false));
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
      workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.UNKNOWN;
    });
    return message;
  }

  @Override
  public synchronized void fail(String reason) {
    log.warn("Marking swarm {} failed: {}", swarmId, reason);
    workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.UNKNOWN;
  }

  @Override
  public boolean hasPendingConfigUpdates() {
    return configFanout.hasPendingAcks();
  }

  @Override
  public synchronized void enableAll() {
    var data = mapper.createObjectNode();
    data.put("enabled", true);
    log.info("Issuing swarm-wide enable config-update for swarm {} (role={} instance={})",
        swarmId, role, instanceId);
    configFanout.publishConfigUpdate(data, "enable");
    workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.RUNNING;
  }

  @Override
  public synchronized void setSwarmEnabled(boolean enabledFlag) {
    if (enabledFlag) {
      enableAll();
    } else {
      disableAll();
      workloadState = io.pockethive.swarm.model.lifecycle.WorkloadState.STOPPED;
    }
  }

  @Override
  public synchronized void setControllerEnabled(boolean enabled) {
    if (this.controllerEnabled == enabled) {
      return;
    }
    this.controllerEnabled = enabled;
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
		        idempotencyKey);
		    log.info("[CTRL] SEND rk={} inst={} correlationId={} (reason={})", rk, instanceId, correlationId, reason);
		    controlPublisher.publishSignal(new io.pockethive.controlplane.messaging.SignalMessage(rk, signal));
		  }

	  private Map<String, Object> runtimeMeta() {
	    String templateId = requireEnvValue("POCKETHIVE_TEMPLATE_ID");
	    String runId = requireEnvValue("POCKETHIVE_JOURNAL_RUN_ID");
	    return Map.of("templateId", templateId, "runId", runId);
	  }

  private Map<String, Bee> beesByRole(List<Bee> bees) {
    if (bees == null || bees.isEmpty()) {
      return Map.of();
    }
    Map<String, Bee> mapping = new LinkedHashMap<>();
    for (Bee bee : bees) {
      if (bee == null || !hasText(bee.role())) {
        continue;
      }
      Bee previous = mapping.putIfAbsent(bee.role(), bee);
      if (previous != null) {
        throw new IllegalStateException("duplicate scenario bee role: " + bee.role());
      }
    }
    return mapping.isEmpty() ? Map.of() : Map.copyOf(mapping);
  }

  private Map<String, String> mapInstancesByRole(Set<String> roles, SwarmRuntimeState state) {
    if (roles == null || roles.isEmpty() || state == null) {
      return Map.of();
    }
    Map<String, List<String>> instancesByRole = state.instancesByRole();
    if (instancesByRole.isEmpty()) {
      return Map.of();
    }
    Map<String, String> mapping = new HashMap<>();
    for (String role : roles) {
      if (!hasText(role)) {
        continue;
      }
      List<String> instances = instancesByRole.get(role);
      if (instances == null || instances.isEmpty()) {
        continue;
      }
      if (instances.size() > 1) {
        throw new IllegalStateException("duplicate runtime worker role: " + role);
      }
      mapping.put(role, instances.getFirst());
    }
    return mapping.isEmpty() ? Map.of() : Map.copyOf(mapping);
  }

  private Map<String, Object> bindingEndpointPayload(TopologyEndpoint endpoint,
                                                     Bee bee,
                                                     Map<String, String> instanceByRole,
                                                     boolean isFrom) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (endpoint == null || bee == null) {
      return payload;
    }
    maybePut(payload, "role", bee.role());
    if (instanceByRole != null && hasText(endpoint.role())) {
      maybePut(payload, "instance", instanceByRole.get(endpoint.role()));
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
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_PICKSTRATEGY", redisMap.get("pickStrategy"));
        putIndexedEnvIfPresent(
            env,
            "POCKETHIVE_INPUTS_REDIS_SOURCES",
            redisMap.get("sources"),
            Map.of("listName", "LISTNAME", "weight", "WEIGHT"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_RATEPERSEC", redisMap.get("ratePerSec"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_INITIALDELAYMS", redisMap.get("initialDelayMs"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_REDIS_TICKINTERVALMS", redisMap.get("tickIntervalMs"));
      }

      Object csv = inputsMap.get("csv");
      if (csv instanceof Map<?, ?> csvMap) {
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_FILEPATH", csvMap.get("filePath"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_RATEPERSEC", csvMap.get("ratePerSec"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_ROTATE", csvMap.get("rotate"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_SKIPHEADER", csvMap.get("skipHeader"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_DELIMITER", csvMap.get("delimiter"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_CHARSET", csvMap.get("charset"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_STARTUPDELAYSECONDS", csvMap.get("startupDelaySeconds"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_TICKINTERVALMS", csvMap.get("tickIntervalMs"));
        putEnvIfPresent(env, "POCKETHIVE_INPUTS_CSV_ENABLED", csvMap.get("enabled"));
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
      Object redis = outputsMap.get("redis");
      if (redis instanceof Map<?, ?> redisMap) {
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_HOST", redisMap.get("host"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_PORT", redisMap.get("port"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_USERNAME", redisMap.get("username"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_PASSWORD", redisMap.get("password"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_SSL", redisMap.get("ssl"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_SOURCESTEP", redisMap.get("sourceStep"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION", redisMap.get("pushDirection"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_DEFAULTLIST", redisMap.get("defaultList"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_TARGETLISTTEMPLATE", redisMap.get("targetListTemplate"));
        putIndexedEnvIfPresent(
            env,
            "POCKETHIVE_OUTPUTS_REDIS_ROUTES",
            redisMap.get("routes"),
            Map.of(
                "match", "MATCH",
                "header", "HEADER",
                "headerMatch", "HEADERMATCH",
                "list", "LIST"));
        putEnvIfPresent(env, "POCKETHIVE_OUTPUTS_REDIS_MAXLEN", redisMap.get("maxLen"));
      }
    }
  }

  private void applyClickHouseSinkEnvironment(Map<String, String> env) {
    if (env == null) {
      return;
    }
    if (!clickHouseSink.configured()) {
      return;
    }
    putEnvIfMissing(env, "POCKETHIVE_SINK_CLICKHOUSE_ENDPOINT", clickHouseSink.getEndpoint());
    putEnvIfMissing(env, "POCKETHIVE_SINK_CLICKHOUSE_TABLE", clickHouseSink.getTable());
    putEnvIfMissing(env, "POCKETHIVE_SINK_CLICKHOUSE_USERNAME", clickHouseSink.getUsername());
    putEnvIfMissing(env, "POCKETHIVE_SINK_CLICKHOUSE_PASSWORD", clickHouseSink.getPassword());
    putEnvIfMissing(
        env,
        "POCKETHIVE_SINK_CLICKHOUSE_CONNECT_TIMEOUT_MS",
        Integer.toString(clickHouseSink.getConnectTimeoutMs()));
    putEnvIfMissing(
        env,
        "POCKETHIVE_SINK_CLICKHOUSE_READ_TIMEOUT_MS",
        Integer.toString(clickHouseSink.getReadTimeoutMs()));
    putEnvIfMissing(
        env,
        "POCKETHIVE_SINK_CLICKHOUSE_BATCH_SIZE",
        Integer.toString(clickHouseSink.getBatchSize()));
    putEnvIfMissing(
        env,
        "POCKETHIVE_SINK_CLICKHOUSE_FLUSH_INTERVAL_MS",
        Integer.toString(clickHouseSink.getFlushIntervalMs()));
    putEnvIfMissing(
        env,
        "POCKETHIVE_SINK_CLICKHOUSE_MAX_BUFFERED_EVENTS",
        Integer.toString(clickHouseSink.getMaxBufferedEvents()));
  }

  private String runtimeStackName() {
    return "ph-" + swarmId.toLowerCase(Locale.ROOT);
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

  private static void putEnvIfMissing(Map<String, String> env, String key, String value) {
    if (env.containsKey(key)) {
      return;
    }
    if (value == null) {
      return;
    }
    String text = value.trim();
    if (!text.isBlank()) {
      env.put(key, text);
    }
  }

  private static void putIndexedEnvIfPresent(
      Map<String, String> env,
      String keyPrefix,
      Object value,
      Map<String, String> fieldEnvNames) {
    if (value == null) {
      return;
    }
    if (!(value instanceof Iterable<?> entries)) {
      throw new IllegalStateException(keyPrefix + " must be a list of objects");
    }
    int index = 0;
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> entryMap)) {
        throw new IllegalStateException(keyPrefix + "_" + index + " must be an object");
      }
      for (Map.Entry<String, String> field : fieldEnvNames.entrySet()) {
        putEnvIfPresent(env, keyPrefix + "_" + index + "_" + field.getValue(), entryMap.get(field.getKey()));
      }
      index++;
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
    if (sutEnvironment.type() == null || sutEnvironment.type().isBlank()) {
      newSut.remove("environmentType");
    } else {
      newSut.put("environmentType", sutEnvironment.type().trim());
    }
    newSut.put("environment", sutEnvironment);
    newSut.put("targetEndpointId", endpointId);
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
