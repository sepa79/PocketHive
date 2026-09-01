package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.CanonicalPayloadDigest;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.runtime.JournalControlPlanePublisher;
import io.pockethive.swarmcontroller.runtime.SwarmControlPlaneJournalErrors;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactLoader;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarmcontroller.runtime.SwarmJournalEntries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Responsibility: Receive control-plane AMQP messages and dispatch them during the staged listener simplification.
 * Must not: Gain new lifecycle, projection, persistence, or terminal-outcome responsibilities.
 * Contract: Preserve accepted routing and command behavior while each remaining legacy workflow is extracted once.
 * TODO: Finish removing lifecycle convergence and config workflows in a separate refactor pass.
 */
@Component
@EnableScheduling
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private final SwarmLifecycle lifecycle;
  private final String instanceId;
  private final ObjectMapper mapper;
  private final ManagerControlPlane controlPlane;
  private final io.pockethive.controlplane.messaging.ControlPlaneEmitter emitter;
  private final String swarmId;
  private final String role;
  private final SwarmJournal journal;
  private final SwarmControlPlaneJournalErrors journalErrors;
  private final String journalRunId;
  private final Map<String, Object> baseRuntimeMeta;
  private final String templateId;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final long LIFECYCLE_CONVERGENCE_TIMEOUT_MS = 30_000L;
  private final AtomicReference<PendingLifecycle> pendingLifecycle = new AtomicReference<>();
  private final AtomicBoolean startupArtifactApplied = new AtomicBoolean(false);
  private final SwarmWorkerStatusHandler workerStatuses;
  private final SwarmWorkerAlertHandler workerAlerts;
  private final SwarmControllerNetworkContext networkContext;
  private final SwarmControllerStatusPublisher statusPublisher;
  private final SwarmStatusFullCoordinator statusFullCoordinator;
  private final SwarmRemoveCommandHandler removeCommands;
  private final io.pockethive.controlplane.codec.ControlPlaneCodec controlPlaneCodec;

  @Autowired
  public SwarmSignalListener(SwarmLifecycle lifecycle,
                             RabbitTemplate rabbit,
                             @Qualifier("instanceId") String instanceId,
                             ObjectMapper mapper,
                             SwarmControllerProperties properties,
                             SwarmJournal journal,
                             @Value("${pockethive.journal.run-id:}") String journalRunId,
                             FilesystemSwarmStartupArtifactLoader startupArtifactLoader,
                             SwarmRemoveCommandHandler removeCommands,
                             SwarmWorkerStatusHandler workerStatuses,
                             SwarmWorkerAlertHandler workerAlerts,
                             SwarmHealthJournal healthJournal,
                             ControlPlaneCodec controlPlaneCodec) {
    this.lifecycle = lifecycle;
    this.instanceId = instanceId;
    this.mapper = mapper.findAndRegisterModules();
    this.removeCommands = Objects.requireNonNull(removeCommands, "removeCommands");
    this.workerStatuses = Objects.requireNonNull(workerStatuses, "workerStatuses");
    this.workerAlerts = Objects.requireNonNull(workerAlerts, "workerAlerts");
    SwarmHealthJournal resolvedHealthJournal = Objects.requireNonNull(healthJournal, "healthJournal");
    this.swarmId = properties.getSwarmId();
    this.role = properties.getRole();
    String controlExchange = properties.getControlExchange();
    this.journal = Objects.requireNonNull(journal, "journal");
    this.journalErrors = new SwarmControlPlaneJournalErrors(this.journal, swarmId, role, instanceId, "swarm-signal-listener");
    this.journalRunId = journalRunId != null && !journalRunId.isBlank() ? journalRunId.trim() : null;
    this.baseRuntimeMeta = buildBaseRuntimeMeta();
    this.templateId = requireEnvValue("POCKETHIVE_TEMPLATE_ID");
    ObjectMapper controlPlaneMapper = ControlPlaneJson.mapper();
    this.controlPlaneCodec = Objects.requireNonNull(controlPlaneCodec, "controlPlaneCodec");
    ControlPlanePublisher basePublisher = new AmqpControlPlanePublisher(
        rabbit, controlExchange, controlPlaneCodec);
    ControlPlanePublisher publisher = new JournalControlPlanePublisher(controlPlaneMapper, this.journal, basePublisher);
    this.controlPlane = ManagerControlPlane.builder(publisher, controlPlaneCodec)
        .identity(new ControlPlaneIdentity(swarmId, role, instanceId))
        .duplicateCache(java.time.Duration.ofMinutes(1), 256)
        .build();
    this.emitter = io.pockethive.controlplane.messaging.ControlPlaneEmitter.swarmController(
        new ControlPlaneIdentity(swarmId, role, instanceId),
        publisher,
        new io.pockethive.controlplane.topology.ControlPlaneTopologySettings(
            swarmId,
            properties.getControlQueuePrefixBase(),
            Map.of())
    , runtimeMetaSnapshot());
    FilesystemSwarmStartupArtifactLoader resolvedLoader =
        Objects.requireNonNull(startupArtifactLoader, "startupArtifactLoader");
    String startupArtifactSha256 = resolvedLoader.expectedSha256();
    this.networkContext = SwarmControllerNetworkContext.fromEnvironment(lifecycle, role);
    this.statusPublisher = new SwarmControllerStatusPublisher(
        lifecycle,
        workerStatuses,
        resolvedHealthJournal,
        properties,
        instanceId,
        publisher,
        runtimeMetaSnapshot(),
        startupArtifactSha256,
        this::isInitialized,
        Instant.now(),
        networkContext);
    this.statusFullCoordinator = new SwarmStatusFullCoordinator(
        lifecycle, statusPublisher, this::isInitialized);
    initializeFromFilesystem(resolvedLoader);
    try {
      statusPublisher.publishFull();
    } catch (Exception e) {
      log.warn("initial status", e);
    }
  }

  private void initializeFromFilesystem(FilesystemSwarmStartupArtifactLoader loader) {
    SwarmStartupArtifact artifact = loader.load(swarmId);
    try {
      lifecycle.prepare(mapper.writeValueAsString(artifact.swarmPlan()));
      lifecycle.applyScenarioPlan(mapper.writeValueAsString(artifact.scenarioPlan()));
      startupArtifactApplied.set(true);
      log.info("Initialized swarm {} from filesystem startup artifact", swarmId);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize verified startup artifact for swarm " + swarmId, e);
    }
  }

  @RabbitListener(queues = "#{swarmControllerControlQueueName}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    // Control-plane messages must never be requeued on failures: ACK (drop) always to avoid storms.
    try {
      if (routingKey == null || routingKey.isBlank()) {
        log.warn("Received control message with null or blank routing key; payload snippet={}", snippet(body));
        journalErrors.errorDrop("event-dropped", routingKey, "missing routing key", body, null);
        return;
      }
      if (body == null || body.isBlank()) {
        log.warn("Received control message with null or blank payload for routing key {}", routingKey);
        journalErrors.errorDrop("event-dropped", routingKey, "missing payload", body, null);
        return;
      }
      String snippet = snippet(body);
      RoutingKey signalKey = ControlPlaneRouting.parseSignal(routingKey);
      RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
      boolean statusEvent = eventKey != null
          && (ControlPlaneEventTypes.METRIC_STATUS_FULL.equals(eventKey.type())
              || ControlPlaneEventTypes.METRIC_STATUS_DELTA.equals(eventKey.type()));
      boolean alertEvent = eventKey != null
          && ControlPlaneEventTypes.ALERT_ALERT.equals(eventKey.type());
      if (statusEvent
          || (signalKey != null && ControlPlaneSignals.STATUS_REQUEST.equals(signalKey.type()))) {
        log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      } else {
        log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      }
      if (signalKey != null) {
        boolean processed = controlPlane.consume(body, routingKey, envelope -> {
          RoutingKey key = ControlPlaneRouting.parseSignal(envelope.routingKey());
          if (!shouldAcceptSignal(key)) {
            log.debug("Ignoring control signal on routing key {}", envelope.routingKey());
            return;
          }
          handleSignal(envelope);
        });
        if (!processed) {
          log.debug("Ignoring control signal on routing key {}", routingKey);
        }
        return;
      } else if (statusEvent) {
        handleStatusEvent(routingKey, body);
      } else if (alertEvent) {
        handleAlertEvent(routingKey, body);
      } else {
        log.warn("Ignoring unsupported control-plane routing key {}; payload snippet={}", routingKey, snippet(body));
        journalErrors.errorDrop("event-dropped", routingKey, "unsupported routing key", body, null);
      }
    } catch (Exception e) {
      log.warn("Ignoring control-plane message due to handler exception; rk={} payload snippet={}", routingKey, snippet(body), e);
      journalErrors.errorDrop("event-dropped", routingKey, "handler exception", body, e);
    } finally {
      MDC.clear();
    }
  }

  private void handleStatusEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null || eventKey.type() == null || !eventKey.type().startsWith("metric.status-")) {
      MissingStatusSegment missingSegment = detectMissingStatusSegment(routingKey);
      if (missingSegment == MissingStatusSegment.ROLE) {
        log.warn("Received status event with missing role on routing key {}; payload snippet={}", routingKey, snippet(body));
        journalErrors.errorDrop("status-parse-error", routingKey, "missing role segment", body, null);
        return;
      }
      if (missingSegment == MissingStatusSegment.INSTANCE) {
        log.warn("Received status event with missing instance on routing key {}; payload snippet={}", routingKey, snippet(body));
        journalErrors.errorDrop("status-parse-error", routingKey, "missing instance segment", body, null);
        return;
      }
      log.warn("Received status event with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("status-parse-error", routingKey, "unparseable routing key", body, null);
      return;
    }
    String role = eventKey.role();
    if (role == null || role.isBlank()) {
      log.warn("Received status event with missing role on routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("status-parse-error", routingKey, "missing role segment", body, null);
      return;
    }
    String instance = eventKey.instance();
    if (instance == null || instance.isBlank()) {
      log.warn("Received status event with missing instance on routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("status-parse-error", routingKey, "missing instance segment", body, null);
      return;
    }
    try {
      io.pockethive.control.StatusMetric status =
          controlPlaneCodec.decode(body, routingKey, io.pockethive.control.StatusMetric.class);
      if (!isLocalSwarm(eventKey.swarmId())) {
        log.debug("Ignoring status for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
        return;
      }
      if (this.role.equalsIgnoreCase(role) && this.instanceId.equalsIgnoreCase(instance)) {
        // Do not treat controller self-status as a worker heartbeat; it skews totals by +1.
        return;
      }
      boolean isStatusFull = isStatusFullEvent(eventKey);
      if (workerStatuses.observe(role, instance, status, isStatusFull)) {
        statusFullCoordinator.maybePublishStartupReady();
      }
      if (isStatusFull) {
        statusPublisher.publishFull();
      }
      tryCompletePendingLifecycle();
      statusFullCoordinator.maybePublishPending();
    } catch (Exception e) {
      log.warn("status parse", e);
      journalErrors.errorDrop("status-parse-error", routingKey, "payload parse", body, e);
    }
  }

  private void handleAlertEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null) {
      log.warn("Received alert with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("alert-parse-error", routingKey, "unparseable routing key", body, null);
      return;
    }
    try {
      AlertMessage alert = controlPlaneCodec.decode(body, routingKey, AlertMessage.class);
      if (!isLocalSwarm(eventKey.swarmId())) {
        log.debug("Ignoring alert for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
        return;
      }
      String payloadSwarm = alert != null && alert.scope() != null ? alert.scope().swarmId() : null;
      String payloadRole = alert != null && alert.scope() != null ? alert.scope().role() : null;
      String payloadInstance = alert != null && alert.scope() != null ? alert.scope().instance() : null;
      warnMissingScopeFields("alert", routingKey, body, payloadSwarm, payloadRole, payloadInstance);
      workerAlerts.handle(routingKey, alert).ifPresent(this::failPendingLifecycle);
    } catch (Exception e) {
      log.warn("alert parse", e);
      journalErrors.errorDrop("alert-parse-error", routingKey, "payload parse", body, e);
    }
  }

  private void failPendingLifecycle(String reason) {
    boolean failed = false;
    PendingLifecycle operation = pendingLifecycle.getAndSet(null);
    if (operation != null) {
      lifecycle.fail(reason);
      emitError(operation.signal(), new IllegalStateException(reason), operation.resolvedSignal(), operation.swarmIdFallback());
      failed = true;
    }
    if (!failed) {
      lifecycle.fail(reason);
      log.warn("Config-update error received with no pending lifecycle command. reason={}", reason);
    }
  }

  private void warnMissingScopeFields(String label,
                                      String routingKey,
                                      String body,
                                      String swarmId,
                                      String role,
                                      String instance) {
    java.util.List<String> missing = new java.util.ArrayList<>();
    if (swarmId == null || swarmId.isBlank()) {
      missing.add("swarmId");
    }
    if (role == null || role.isBlank()) {
      missing.add("role");
    }
    if (instance == null || instance.isBlank()) {
      missing.add("instance");
    }
    if (!missing.isEmpty()) {
      log.warn("Received {} payload with missing scope fields {}; rk={} payload snippet={}",
          label, missing, routingKey, snippet(body));
      journalErrors.errorDrop("event-dropped", routingKey, "missing scope fields: " + String.join(",", missing), body, null);
    }
  }

  private void processSwarmSignal(ControlSignal cs,
                                  String resolvedSignal,
                                  String swarmId,
                                  SignalAction action,
                                  String label) {
    MDC.put("correlation_id", cs.correlationId());
    MDC.put("idempotency_key", cs.idempotencyKey());
    try {
      long freshnessCutoffMillis = System.currentTimeMillis();
      log.info("{} signal for swarm {}", label.substring(0, 1).toUpperCase() + label.substring(1), swarmId);
      action.apply(serializeArgs(cs));
      if (ControlPlaneSignals.SWARM_START.equals(resolvedSignal)
          || ControlPlaneSignals.SWARM_STOP.equals(resolvedSignal)) {
        boolean expectedEnabled = ControlPlaneSignals.SWARM_START.equals(resolvedSignal);
        PendingLifecycle next = new PendingLifecycle(
            cs, resolvedSignal, swarmId, freshnessCutoffMillis, expectedEnabled,
            freshnessCutoffMillis + LIFECYCLE_CONVERGENCE_TIMEOUT_MS);
        if (!pendingLifecycle.compareAndSet(null, next)) {
          throw new IllegalStateException("Another lifecycle command is awaiting convergence");
        }
        tryCompletePendingLifecycle();
      } else {
        emitSuccess(cs, resolvedSignal, swarmId);
      }
    } catch (Exception e) {
      log.warn(label, e);
      emitError(cs, e, resolvedSignal, swarmId);
    }
  }

  private void tryCompletePendingLifecycle() {
    while (true) {
      PendingLifecycle pending = pendingLifecycle.get();
      if (pending == null) {
        return;
      }
      List<Target> nonConverged = lifecycle.nonConvergedWorkersSince(
          pending.freshnessCutoffMillis(), pending.expectedEnabled());
      boolean blocked = pending.expectedEnabled()
          && (lifecycle.hasPendingConfigUpdates() || !lifecycle.isReadyForWork());
      boolean timedOut = System.currentTimeMillis() >= pending.deadlineMillis();
      if ((blocked || !nonConverged.isEmpty()) && !timedOut) {
        return;
      }
      if (pendingLifecycle.compareAndSet(pending, null)) {
        Map<String, Object> evidence = Map.of("nonConvergedWorkers", nonConverged);
        TerminalStatus status = blocked || !nonConverged.isEmpty()
            ? TerminalStatus.FAILED
            : TerminalStatus.SUCCEEDED;
        emitSuccess(
            pending.signal(), pending.resolvedSignal(), pending.swarmIdFallback(),
            terminalResult(pending.signal(), pending.resolvedSignal(), status, evidence));
        statusFullCoordinator.queueAfterLifecycle(pending.freshnessCutoffMillis());
        return;
      }
    }
  }

  private void processConfigUpdate(ControlSignalEnvelope envelope, String resolvedSignal) {
    ControlSignal cs = envelope.signal();
    MDC.put("correlation_id", cs.correlationId());
    MDC.put("idempotency_key", cs.idempotencyKey());
    RoutingKey key = ControlPlaneRouting.parseSignal(envelope.routingKey());
    if (!shouldProcessConfigUpdate(key)) {
      log.debug("Ignoring config-update on routing key {}", envelope.routingKey());
      return;
    }
    io.pockethive.control.ControlScope scope = cs.scope();
    String targetRole = scope != null ? scope.role() : null;
    String targetInstance = scope != null ? scope.instance() : null;
    boolean roleAll = targetRole == null || isAllSegment(targetRole);
    boolean instanceAll = targetInstance == null || isAllSegment(targetInstance);
    boolean fromSelf = cs.origin() != null && instanceId.equalsIgnoreCase(cs.origin());
    if (fromSelf && roleAll && instanceAll) {
      log.debug("Ignoring self-issued broadcast config-update; rk={} corr={}",
          envelope.routingKey(), cs.correlationId());
      return;
    }
    JsonNode node = mapper.createObjectNode();
    if (cs.data() != null) {
      node = mapper.valueToTree(cs.data());
    }
    boolean networkContextOnly = networkContext.isOnlyNetworkContext(targetRole, node);
    if (rejectIfNotReady(cs, resolvedSignal, swarmIdOrDefault(cs), !networkContextOnly)) {
      return;
    }

    try {
      JsonNode dataNode = node;
      Boolean enabledFlag = dataNode.has("enabled") ? dataNode.path("enabled").asBoolean() : null;
      boolean networkContextChanged = networkContext.applyOverride(dataNode);

      Map<String, Object> details = new LinkedHashMap<>();
      boolean scenarioChanged = false;

      // Optional buffer guard overrides live under data.trafficPolicy.bufferGuard
      JsonNode guardRoot = dataNode.path("trafficPolicy").path("bufferGuard");
      if (guardRoot.isObject()) {
        List<BufferGuardSettings> currentGuards = lifecycle.bufferGuards();
        if (currentGuards.isEmpty()) {
          log.warn("Received bufferGuard override but no guards are configured from the scenario; ignoring override");
        } else {
          BufferGuardSettings base = currentGuards.getFirst();
          BufferGuardSettings updated = applyGuardOverride(base, guardRoot);
          if (updated == null) {
            // disabled via enabled=false
            lifecycle.configureBufferGuards(List.of());
            details.put("trafficPolicy", Map.of("bufferGuard", Map.of("enabled", false)));
          } else {
            lifecycle.configureBufferGuards(List.of(updated));
            TrafficPolicy effectivePolicy = BufferGuardTrafficPolicyMapper.toTrafficPolicy(updated);
            details.put("trafficPolicy", mapper.convertValue(effectivePolicy, MAP_TYPE));
          }
        }
      }

      boolean controllerTarget = targetRole != null && this.role.equalsIgnoreCase(targetRole);

      if (controllerTarget) {
        if (enabledFlag != null) {
          lifecycle.setSwarmEnabled(enabledFlag);
          statusPublisher.publishDelta();
        }
        ScenarioChange change = applyScenarioOverrides(dataNode);
        scenarioChanged = scenarioChanged || change.changed();
        if (change.details() != null && !change.details().isEmpty()) {
          details.put("scenario", change.details());
        }
      } else if (roleAll) {
        // config-update signals for non-controller roles are routed directly to those roles/instances.
      }

      if (scenarioChanged || networkContextChanged) {
        statusPublisher.publishDelta();
      }
      TerminalResult state = configCommandResult(cs, details, TerminalStatus.SUCCEEDED);
      emitSuccess(cs, resolvedSignal, null, state);
    } catch (Exception e) {
      log.warn("config update", e);
      emitError(cs, e, resolvedSignal, null);
    }
  }

  private boolean rejectIfNotReady(ControlSignal cs,
                                   String resolvedSignal,
                                   String swarmIdFallback,
                                   boolean requireRunning) {
    boolean initialized = isInitialized();
    boolean ready = lifecycle.isReadyForWork();
    boolean pendingConfigUpdates = lifecycle.hasPendingConfigUpdates();
    WorkloadState status = lifecycle.getWorkloadState();
    boolean running = status == WorkloadState.RUNNING;
    if (initialized && ready && !pendingConfigUpdates && (!requireRunning || running)) {
      return false;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("initialized", initialized);
    details.put("ready", ready);
    details.put("pendingConfigUpdates", pendingConfigUpdates);
    if (status != null) {
      details.put("status", status.name());
    }
    log.warn("[CTRL] command rejected operation={} phase={} code={} message={} swarmId={} role={} instance={} correlationId={} idempotencyKey={} retryable={} initialized={} ready={} pendingConfigUpdates={} status={}",
        resolvedSignal, phaseForSignal(resolvedSignal), "not-ready",
        "Swarm controller is not ready for this operation", swarmIdFallback, role, instanceId,
        cs.correlationId(), cs.idempotencyKey(), true, initialized, ready, pendingConfigUpdates,
        status != null ? status.name() : "unknown");
    TerminalResult result = terminalResult(cs, resolvedSignal, TerminalStatus.REJECTED, details);
    emitSuccess(cs, resolvedSignal, swarmIdFallback, result);
    return true;
  }

  private boolean completeLifecycleIfAlreadyAchieved(
      ControlSignal cs, String resolvedSignal, String swarmIdFallback) {
    WorkloadState requested = ControlPlaneSignals.SWARM_START.equals(resolvedSignal)
        ? WorkloadState.RUNNING
        : WorkloadState.STOPPED;
    if (lifecycle.getWorkloadState() != requested) {
      return false;
    }
    log.info("Lifecycle command already achieved operation={} swarmId={} workloadState={} correlationId={} idempotencyKey={}",
        resolvedSignal, swarmIdFallback, requested, cs.correlationId(), cs.idempotencyKey());
    emitSuccess(cs, resolvedSignal, swarmIdFallback,
        terminalResult(cs, resolvedSignal, TerminalStatus.SUCCEEDED, Map.of()));
    return true;
  }

  private boolean isInitialized() {
    return startupArtifactApplied.get();
  }

  private void handleSignal(ControlSignalEnvelope envelope) {
    ControlSignal cs = envelope.signal();
    if (cs == null) {
      return;
    }
    String signal = resolveSignal(envelope);
    if (signal != null && !ControlPlaneSignals.STATUS_REQUEST.equals(signal)) {
      journal.append(SwarmJournalEntries.inSignal(mapper, envelope.routingKey(), cs));
    }
    switch (signal) {
      case ControlPlaneSignals.SWARM_START -> {
        if (isForLocalSwarm(cs)) {
          if (rejectIfNotReady(cs, signal, swarmIdOrDefault(cs), false)) {
            return;
          }
          if (completeLifecycleIfAlreadyAchieved(cs, signal, swarmIdOrDefault(cs))) {
            return;
          }
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> {
            lifecycle.start(args);
          }, "start");
        }
      }
      case ControlPlaneSignals.SWARM_STOP -> {
        if (isForLocalSwarm(cs)) {
          if (rejectIfNotReady(cs, signal, swarmIdOrDefault(cs), false)) {
            return;
          }
          if (completeLifecycleIfAlreadyAchieved(cs, signal, swarmIdOrDefault(cs))) {
            return;
          }
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> {
            lifecycle.stop();
          }, "stop");
        }
      }
      case ControlPlaneSignals.SWARM_REMOVE -> {
        if (isForLocalSwarm(cs)) {
          removeCommands.handle(cs);
        }
      }
      case ControlPlaneSignals.STATUS_REQUEST -> {
        log.debug("Status request received: {}", envelope.routingKey());
        statusPublisher.publishFull();
      }
      case ControlPlaneSignals.CONFIG_UPDATE -> processConfigUpdate(envelope, signal);
      default -> {
        // ignore other signals
      }
    }
  }

  private boolean shouldAcceptSignal(RoutingKey key) {
    if (key == null) {
      return false;
    }
    if (!isLocalSwarm(key.swarmId())) {
      return false;
    }
    String roleSegment = defaultSegment(key.role(), role);
    boolean roleMatchesController = role.equalsIgnoreCase(roleSegment) || isAllSegment(roleSegment);
    if (!roleMatchesController) {
      String type = defaultSegment(key.type(), null);
      if (ControlPlaneSignals.CONFIG_UPDATE.equalsIgnoreCase(type)) {
        return true;
      }
      return false;
    }
    String instanceSegment = key.instance();
    if (isAllSegment(instanceSegment)) {
      return true;
    }
    return instanceId.equalsIgnoreCase(instanceSegment);
  }

  private boolean shouldProcessConfigUpdate(RoutingKey key) {
    if (key == null) {
      return false;
    }
    if (!isLocalSwarm(key.swarmId())) {
      return false;
    }
    String roleSegment = defaultSegment(key.role(), role);
    boolean roleMatchesController = role.equalsIgnoreCase(roleSegment) || isAllSegment(roleSegment);
    if (!roleMatchesController) {
      return false;
    }
    String targetInstance = key.instance();
    if (isAllSegment(targetInstance)) {
      return true;
    }
    return instanceId.equalsIgnoreCase(targetInstance);
  }

  @FunctionalInterface
  private interface SignalAction {
    void apply(String argsJson) throws Exception;
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    statusPublisher.publishDelta();
    statusFullCoordinator.maybePublishStartupReady();
    tryCompletePendingLifecycle();
    statusFullCoordinator.maybePublishPending();
  }

  private void emitSuccess(ControlSignal cs, String resolvedSignal, String swarmIdFallback) {
    emitSuccess(cs, resolvedSignal, swarmIdFallback, null);
  }

  private void emitSuccess(ControlSignal cs,
                           String resolvedSignal,
                           String swarmIdFallback,
                           TerminalResult overrideResult) {
    String signal = requireSignal(confirmationSignal(cs, resolvedSignal), "Result");
    TerminalResult result = overrideResult != null
        ? overrideResult
        : terminalResult(cs, signal, TerminalStatus.SUCCEEDED, Map.of());
    emitter.emitResult(new io.pockethive.controlplane.messaging.ControlPlaneEmitter.ResultContext(
        signal, cs.correlationId(), cs.idempotencyKey(), result, Instant.now()));
  }

  private void emitError(ControlSignal cs,
                         Exception e,
                         String resolvedSignal,
                         String swarmIdFallback) {
    String signal = requireSignal(confirmationSignal(cs, resolvedSignal), "Result");
    String code = e.getClass().getSimpleName();
    String message = e.getMessage() == null || e.getMessage().isBlank() ? code : e.getMessage();
    emitter.emitFailure(new io.pockethive.controlplane.messaging.ControlPlaneEmitter.FailureContext(
        signal,
        cs.correlationId(),
        cs.idempotencyKey(),
        terminalResult(cs, signal, TerminalStatus.FAILED, Map.of()),
        phaseForSignal(signal),
        code,
        message,
        e.getClass().getName(),
        e.getMessage(),
        null,
        Instant.now()));
  }

  private TerminalResult configCommandResult(
      ControlSignal signal, Map<String, Object> details, TerminalStatus terminalStatus) {
    WorkloadState status = lifecycle.getWorkloadState();
    boolean enabled = status != null && workloadsEnabled(status);
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("target", target(signal));
    JsonNode data = mapper.valueToTree(signal.data());
    context.put("requestedEnabled", data.has("enabled") ? data.path("enabled").asBoolean() : null);
    context.put("observedEnabled", enabled);
    context.put("appliedConfigSha256",
        terminalStatus == TerminalStatus.SUCCEEDED
            ? CanonicalPayloadDigest.sha256(mapper, signal.data())
            : null);
    return new TerminalResult(terminalStatus, false, context);
  }

  private TerminalResult terminalResult(
      ControlSignal controlSignal,
      String signal,
      TerminalStatus terminalStatus,
      Map<String, Object> details) {
    if (ControlPlaneSignals.CONFIG_UPDATE.equals(signal)) {
      return configCommandResult(controlSignal, details, terminalStatus);
    }
    if (ControlPlaneSignals.SWARM_REMOVE.equals(signal)) {
      throw new IllegalStateException("swarm-remove result is filesystem-only");
    }
    WorkloadState current = lifecycle.getWorkloadState();
    String observed = current == null ? WorkloadState.UNKNOWN.name() : current.name();
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("target", target(controlSignal));
    context.put("requestedWorkloadState",
        ControlPlaneSignals.SWARM_START.equals(signal) ? "RUNNING" : "STOPPED");
    context.put("observedWorkloadState", observed);
    Object nonConverged = details == null ? null : details.get("nonConvergedWorkers");
    context.put("nonConvergedWorkers", nonConverged instanceof List<?> list ? list : List.of());
    return new TerminalResult(terminalStatus, terminalStatus == TerminalStatus.FAILED, context);
  }

  private Target target(ControlSignal signal) {
    ControlScope scope = signal.scope();
    return new Target(
        scope == null || ControlScope.isAll(scope.role()) ? role : scope.role(),
        scope == null || ControlScope.isAll(scope.instance()) ? instanceId : scope.instance());
  }

  private boolean isForLocalSwarm(ControlSignal cs) {
    return appliesToLocalSwarm(cs);
  }

  private String swarmIdOrDefault(ControlSignal cs) {
    String targetSwarm = cs.scope() != null ? cs.scope().swarmId() : null;
    if (targetSwarm == null || targetSwarm.isBlank() || isAllSegment(targetSwarm)) {
      return this.swarmId;
    }
    return targetSwarm;
  }

  private String serializeArgs(ControlSignal cs) {
    Map<String, Object> args = cs.data();
    if (args == null || args.isEmpty()) {
      return "{}";
    }
    try {
      return mapper.writeValueAsString(args);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to serialize control signal args", ex);
    }
  }

  private boolean appliesToLocalSwarm(ControlSignal cs) {
    String targetSwarm = cs.scope() != null ? cs.scope().swarmId() : null;
    if (targetSwarm == null || targetSwarm.isBlank() || isAllSegment(targetSwarm)) {
      return true;
    }
    return swarmId.equalsIgnoreCase(targetSwarm);
  }

  private String resolveSignal(ControlSignalEnvelope envelope) {
    return envelope.signal().type();
  }

  private record PendingLifecycle(
      ControlSignal signal,
      String resolvedSignal,
      String swarmIdFallback,
      long freshnessCutoffMillis,
      boolean expectedEnabled,
      long deadlineMillis) {}

  private boolean isStatusFullEvent(RoutingKey key) {
    if (key == null || key.type() == null) {
      return false;
    }
    return ControlPlaneEventTypes.METRIC_STATUS_FULL.equals(key.type());
  }

  private String phaseForSignal(String signal) {
    if (signal == null || signal.isBlank()) {
      return signal;
    }
    return switch (signal) {
      case ControlPlaneSignals.SWARM_START -> "start";
      case ControlPlaneSignals.SWARM_STOP -> "stop";
      case ControlPlaneSignals.SWARM_REMOVE -> "remove";
      case ControlPlaneSignals.CONFIG_UPDATE -> ControlPlaneSignals.CONFIG_UPDATE;
      default -> signal;
    };
  }

  private String confirmationSignal(ControlSignal cs, String resolvedSignal) {
    return defaultSegment(cs != null ? cs.type() : null, resolvedSignal);
  }

  private String requireSignal(String signal, String context) {
    if (signal == null || signal.isBlank()) {
      throw new IllegalArgumentException(context + " requires a resolved control signal");
    }
    return signal;
  }

  private Map<String, Object> buildBaseRuntimeMeta() {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("containerId", envValue("HOSTNAME"));
    meta.put("image", envValue("POCKETHIVE_RUNTIME_IMAGE"));
    meta.put("stackName", runtimeStackName());
    return Collections.unmodifiableMap(meta);
  }

  private Map<String, Object> runtimeMetaSnapshot() {
    Map<String, Object> meta = new LinkedHashMap<>(baseRuntimeMeta);
    meta.put("templateId", templateId);
    meta.put("runId", requireNonBlank(journalRunId, "pockethive.journal.run-id"));
    return Collections.unmodifiableMap(meta);
  }

  private static String requireNonBlank(String value, String context) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(context + " must not be blank");
    }
    return value.trim();
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

  private ScenarioChange applyScenarioOverrides(JsonNode dataNode) {
    JsonNode scenarioNode = dataNode.path("scenario");
    if (!scenarioNode.isObject()) {
      return ScenarioChange.none();
    }
    boolean changed = false;
    Map<String, Object> detail = new LinkedHashMap<>();
    if (scenarioNode.has("runs")) {
      int runs = scenarioNode.path("runs").asInt(-1);
      if (runs > 0) {
        lifecycle.setScenarioRuns(runs);
        detail.put("runs", runs);
        changed = true;
      } else {
        log.warn("Ignoring scenario.runs override {}; value must be >= 1", scenarioNode.path("runs").asText());
      }
    }
    if (scenarioNode.path("reset").asBoolean(false)) {
      lifecycle.resetScenarioPlan();
      detail.put("reset", true);
      changed = true;
    }
    return changed ? new ScenarioChange(true, detail.isEmpty() ? null : detail) : ScenarioChange.none();
  }

  private record ScenarioChange(boolean changed, Map<String, Object> details) {
    static ScenarioChange none() {
      return new ScenarioChange(false, null);
    }
  }

  private BufferGuardSettings applyGuardOverride(BufferGuardSettings base, JsonNode guardNode) {
    if (base == null || guardNode == null || !guardNode.isObject()) {
      return base;
    }
    boolean hasEnabled = guardNode.has("enabled");
    boolean enabled = hasEnabled && guardNode.path("enabled").asBoolean();
    if (hasEnabled && !enabled) {
      // Disabled: the caller intends to turn the guard off entirely.
      return null;
    }

    String queueAliasOverride = textOrNull(guardNode.path("queueAlias"));
    if (queueAliasOverride != null
        && !queueAliasOverride.equalsIgnoreCase(base.queueAlias())) {
      throw new IllegalArgumentException(
          "Changing buffer guard queueAlias at runtime is not supported; edit the scenario plan instead");
    }

    int targetDepth = intOr(guardNode, "targetDepth", base.targetDepth());
    int minDepth = intOr(guardNode, "minDepth", base.minDepth());
    int maxDepth = intOr(guardNode, "maxDepth", base.maxDepth());
    String samplePeriodStr = textOrNull(guardNode.path("samplePeriod"));
    java.time.Duration samplePeriod = samplePeriodStr != null
        ? java.time.Duration.parse(samplePeriodStr.toUpperCase(java.util.Locale.ROOT))
        : base.samplePeriod();
    int movingAverageWindow = intOr(guardNode, "movingAverageWindow", base.movingAverageWindow());

    JsonNode adjustNode = guardNode.path("adjust");
    BufferGuardSettings.Adjustment baseAdj = base.adjust();
    int maxIncreasePct = intOr(adjustNode, "maxIncreasePct", baseAdj.maxIncreasePct());
    int maxDecreasePct = intOr(adjustNode, "maxDecreasePct", baseAdj.maxDecreasePct());
    int minRatePerSec = intOr(adjustNode, "minRatePerSec", baseAdj.minRatePerSec());
    int maxRatePerSec = intOr(adjustNode, "maxRatePerSec", baseAdj.maxRatePerSec());
    BufferGuardSettings.Adjustment adj = new BufferGuardSettings.Adjustment(
        maxIncreasePct, maxDecreasePct, minRatePerSec, maxRatePerSec);

    JsonNode prefillNode = guardNode.path("prefill");
    BufferGuardSettings.Prefill basePrefill = base.prefill();
    boolean prefillEnabled = prefillNode.isMissingNode()
        ? basePrefill.enabled()
        : prefillNode.path("enabled").asBoolean(basePrefill.enabled());
    String lookaheadStr = textOrNull(prefillNode.path("lookahead"));
    java.time.Duration lookahead = lookaheadStr != null
        ? java.time.Duration.parse(lookaheadStr.toUpperCase(java.util.Locale.ROOT))
        : basePrefill.lookahead();
    int liftPct = intOr(prefillNode, "liftPct", basePrefill.liftPct());
    BufferGuardSettings.Prefill prefill = new BufferGuardSettings.Prefill(prefillEnabled, lookahead, liftPct);

    JsonNode bpNode = guardNode.path("backpressure");
    BufferGuardSettings.Backpressure baseBp = base.backpressure();
    String bpAliasOverride = textOrNull(bpNode.path("queueAlias"));
    String bpQueueAlias = bpAliasOverride != null ? bpAliasOverride : baseBp.queueAlias();
    int highDepth = intOr(bpNode, "highDepth", baseBp.highDepth());
    int recoveryDepth = intOr(bpNode, "recoveryDepth", baseBp.recoveryDepth());
    int moderatorReductionPct = intOr(bpNode, "moderatorReductionPct", baseBp.moderatorReductionPct());
    BufferGuardSettings.Backpressure backpressure =
        new BufferGuardSettings.Backpressure(bpQueueAlias, baseBp.queueName(), highDepth, recoveryDepth, moderatorReductionPct);

    return new BufferGuardSettings(
        base.queueAlias(),
        base.queueName(),
        base.targetRole(),
        base.initialRatePerSec(),
        targetDepth,
        minDepth,
        maxDepth,
        samplePeriod,
        movingAverageWindow,
        adj,
        prefill,
        backpressure);
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || !node.isTextual()) {
      return null;
    }
    String trimmed = node.asText().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int intOr(JsonNode node, String field, int fallback) {
    if (node == null || !node.has(field)) {
      return fallback;
    }
    return node.path(field).isInt() ? node.path(field).asInt() : fallback;
  }
  private boolean workloadsEnabled(WorkloadState status) {
    return status == WorkloadState.RUNNING || status == WorkloadState.STARTING;
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

  private MissingStatusSegment detectMissingStatusSegment(String routingKey) {
    if (routingKey == null) {
      return null;
    }
    String[] segments = routingKey.split("\\.", -1);
    if (segments.length < 5) {
      return null;
    }
    String roleSegment = segments[segments.length - 2];
    if (roleSegment == null || roleSegment.isBlank()) {
      return MissingStatusSegment.ROLE;
    }
    String instanceSegment = segments[segments.length - 1];
    if (instanceSegment == null || instanceSegment.isBlank()) {
      return MissingStatusSegment.INSTANCE;
    }
    return null;
  }

  private enum MissingStatusSegment {
    ROLE,
    INSTANCE
  }

  private String defaultSegment(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

		  private boolean isAllSegment(String value) {
		    return ControlScope.isAll(value);
		  }

  private boolean isLocalSwarm(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return isAllSegment(value) || swarmId.equalsIgnoreCase(value);
  }
}
