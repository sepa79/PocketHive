package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandState;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.runtime.JournalControlPlanePublisher;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@EnableScheduling
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private final SwarmLifecycle lifecycle;
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final ObjectMapper mapper;
  private final ManagerControlPlane controlPlane;
  private final io.pockethive.controlplane.messaging.ControlPlaneEmitter emitter;
  private final SwarmControllerProperties properties;
  private final String swarmId;
  private final String role;
  private final String controlExchange;
  private final SwarmDiagnosticsAggregator diagnostics;
  private final SwarmIoStateAggregator ioStates;
  private final SwarmWorkersAggregator workers;
  private final SwarmJournal journal;
  private final String journalRunId;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final long MAX_STALENESS_MS = 15_000L;
  private final AtomicReference<PendingTemplate> pendingTemplate = new AtomicReference<>();
  private final AtomicReference<PendingStart> pendingStart = new AtomicReference<>();
  private final AtomicBoolean templateApplied = new AtomicBoolean(false);
  private final AtomicBoolean planApplied = new AtomicBoolean(false);
  private final java.time.Instant startedAt;
  private volatile String lastHealthState;
  private volatile Instant healthJournalSuppressUntil;
  private volatile boolean healthWorkloadsEnabled;
  private final ConcurrentMap<String, Long> lastWorkerErrorCounts = new ConcurrentHashMap<>();

  @Autowired
  public SwarmSignalListener(SwarmLifecycle lifecycle,
                             RabbitTemplate rabbit,
                             @Qualifier("instanceId") String instanceId,
                             ObjectMapper mapper,
                             SwarmControllerProperties properties,
                             SwarmJournal journal,
                             @Value("${pockethive.journal.run-id:}") String journalRunId) {
    this.lifecycle = lifecycle;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.mapper = mapper.findAndRegisterModules();
    this.properties = properties;
    this.swarmId = properties.getSwarmId();
    this.role = properties.getRole();
    this.controlExchange = properties.getControlExchange();
    this.diagnostics = new SwarmDiagnosticsAggregator(this.mapper);
    this.ioStates = new SwarmIoStateAggregator();
    this.workers = new SwarmWorkersAggregator(MAX_STALENESS_MS);
    this.journal = journal != null ? journal : SwarmJournal.noop();
    this.journalRunId = journalRunId != null && !journalRunId.isBlank() ? journalRunId.trim() : null;
    ObjectMapper controlPlaneMapper = ControlPlaneJson.mapper();
    ControlPlanePublisher basePublisher = new AmqpControlPlanePublisher(rabbit, controlExchange);
    ControlPlanePublisher publisher = new JournalControlPlanePublisher(controlPlaneMapper, this.journal, basePublisher);
    this.controlPlane = ManagerControlPlane.builder(publisher, controlPlaneMapper)
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
    );
    this.startedAt = java.time.Instant.now();
    this.lastHealthState = null;
    this.healthJournalSuppressUntil = null;
    this.healthWorkloadsEnabled = false;
    try {
      sendStatusFull();
    } catch (Exception e) {
      log.warn("initial status", e);
    }
  }

  @RabbitListener(queues = "#{swarmControllerControlQueueName}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    try {
      if (routingKey == null || routingKey.isBlank()) {
        log.warn("Received control message with null or blank routing key; payload snippet={}", snippet(body));
        throw new IllegalArgumentException("Control-plane routing key must not be null or blank");
      }
      String snippet = snippet(body);
      if (routingKey.startsWith("event.metric.status-")
          || routingKey.startsWith("event.status-")
          || routingKey.startsWith("signal." + ControlPlaneSignals.STATUS_REQUEST)) {
        log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      } else {
        log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      }
      if (routingKey.startsWith("signal.")) {
        RoutingKey key = ControlPlaneRouting.parseSignal(routingKey);
        if (!shouldAcceptSignal(key)) {
          log.debug("Ignoring control signal on routing key {}", routingKey);
          return;
        }
        boolean processed = controlPlane.consume(body, routingKey, envelope -> handleSignal(envelope, body));
        if (!processed) {
          log.debug("Ignoring control signal on routing key {}", routingKey);
        }
        return;
      } else if (routingKey.startsWith("event.metric.status-")) {
        handleStatusEvent(routingKey, body);
      } else if (routingKey.startsWith("event.alert.")) {
        handleAlertEvent(routingKey, body);
      }
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
        throw new IllegalArgumentException("Status event routing key must include a role segment");
      }
      if (missingSegment == MissingStatusSegment.INSTANCE) {
        log.warn("Received status event with missing instance on routing key {}; payload snippet={}", routingKey, snippet(body));
        throw new IllegalArgumentException("Status event routing key must include an instance segment");
      }
      log.warn("Received status event with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      throw new IllegalArgumentException("Status event routing key must resolve to a confirmation scope");
    }
    if (!isLocalSwarm(eventKey.swarmId())) {
      log.debug("Ignoring status for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
      return;
    }
    String role = eventKey.role();
    if (role == null || role.isBlank()) {
      log.warn("Received status event with missing role on routing key {}; payload snippet={}", routingKey, snippet(body));
      throw new IllegalArgumentException("Status event routing key must include a role segment");
    }
    String instance = eventKey.instance();
    if (instance == null || instance.isBlank()) {
      log.warn("Received status event with missing instance on routing key {}; payload snippet={}", routingKey, snippet(body));
      throw new IllegalArgumentException("Status event routing key must include an instance segment");
    }
    if (this.role.equalsIgnoreCase(role) && this.instanceId.equalsIgnoreCase(instance)) {
      // Do not treat controller self-status as a worker heartbeat; it skews totals by +1.
      return;
    }
    try {
      JsonNode node = mapper.readTree(body);
      JsonNode scopeNode = node.path("scope");
      String payloadSwarm = scopeNode.path("swarmId").asText(null);
      String payloadRole = scopeNode.path("role").asText(null);
      String payloadInstance = scopeNode.path("instance").asText(null);
      warnMissingScopeFields("status", routingKey, body, payloadSwarm, payloadRole, payloadInstance);
      if (payloadSwarm != null && !payloadSwarm.isBlank() && !"ALL".equalsIgnoreCase(payloadSwarm)
          && !swarmId.equals(payloadSwarm)) {
        log.debug("Ignoring status payload for swarm {} on routing key {}", payloadSwarm, routingKey);
        return;
      }
      lifecycle.updateHeartbeat(role, instance);
      diagnostics.updateFromWorkerStatus(role, instance, node.path("data"));
      ioStates.updateFromWorkerStatus(role, instance, node.path("data"));
      workers.updateFromWorkerStatus(role, instance, node.path("data"));
      maybeJournalWorkerErrorIndicators(role, instance, node);

      boolean enabled = node.path("data").path("enabled").asBoolean(true);
      lifecycle.updateEnabled(role, instance, enabled);
      if (!enabled) {
        boolean ready = lifecycle.markReady(role, instance);
        if (ready) {
          tryEmitPendingTemplateReady();
          tryEmitPendingStartReady();
        }
      }
    } catch (Exception e) {
      log.warn("status parse", e);
    }
  }

  private void maybeJournalWorkerErrorIndicators(String workerRole, String workerInstance, JsonNode statusEnvelope) {
    if (workerRole == null || workerRole.isBlank() || workerInstance == null || workerInstance.isBlank()) {
      return;
    }
    if (statusEnvelope == null || !statusEnvelope.isObject()) {
      return;
    }
    JsonNode data = statusEnvelope.path("data");
    if (!data.isObject()) {
      return;
    }
    JsonNode context = data.path("context");
    if (!context.isObject()) {
      return;
    }
    JsonNode errorCountNode = context.get("errorCount");
    if (errorCountNode == null || !errorCountNode.isNumber()) {
      return;
    }
    long current = errorCountNode.asLong(0L);
    if (current <= 0L) {
      return;
    }
    String key = workerRole + ":" + workerInstance;
    long previous = lastWorkerErrorCounts.getOrDefault(key, 0L);
    if (current <= previous) {
      lastWorkerErrorCounts.put(key, current);
      return;
    }
    lastWorkerErrorCounts.put(key, current);

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("role", workerRole);
    entry.put("instance", workerInstance);
    entry.put("errorCount", current);
    entry.put("errorDelta", current - previous);
    JsonNode errorTpsNode = context.get("errorTps");
    if (errorTpsNode != null && errorTpsNode.isNumber()) {
      entry.put("errorTps", errorTpsNode.numberValue());
    }
    JsonNode serviceIdNode = context.get("serviceId");
    if (serviceIdNode != null && serviceIdNode.isTextual() && !serviceIdNode.asText().isBlank()) {
      entry.put("serviceId", serviceIdNode.asText());
    }
    JsonNode templateRootNode = context.get("templateRoot");
    if (templateRootNode != null && templateRootNode.isTextual() && !templateRootNode.asText().isBlank()) {
      entry.put("templateRoot", templateRootNode.asText());
    }

    journal.append(SwarmJournalEntries.local(
        swarmId,
        "ERROR",
        "worker-error",
        instanceId,
        io.pockethive.control.ControlScope.forInstance(swarmId, workerRole, workerInstance),
        entry,
        Map.of("source", "status-delta")));
  }

  private void handleAlertEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null) {
      log.warn("Received alert with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      return;
    }
    if (!isLocalSwarm(eventKey.swarmId())) {
      log.debug("Ignoring alert for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
      return;
    }
    try {
      AlertMessage alert = mapper.readValue(body, AlertMessage.class);
      String payloadSwarm = alert != null && alert.scope() != null ? alert.scope().swarmId() : null;
      String payloadRole = alert != null && alert.scope() != null ? alert.scope().role() : null;
      String payloadInstance = alert != null && alert.scope() != null ? alert.scope().instance() : null;
      warnMissingScopeFields("alert", routingKey, body, payloadSwarm, payloadRole, payloadInstance);
      if (alert != null && instanceId.equals(alert.origin())) {
        // Avoid duplicating alerts that the controller itself emitted (they are already journaled as OUT).
        return;
      }
      journal.append(SwarmJournalEntries.inAlert(mapper, routingKey, alert));
      String phase = alert.data() != null && alert.data().context() != null
          ? textOrNull(mapper.valueToTree(alert.data().context()).path("phase"))
          : null;
      if (!"config-update".equalsIgnoreCase(phase)) {
        return;
      }
      String role = alert.scope() != null ? alert.scope().role() : null;
      String instance = alert.scope() != null ? alert.scope().instance() : null;
      String message = alert.data() != null ? alert.data().message() : null;
      lifecycle.handleConfigUpdateError(role, instance, message)
          .ifPresent(this::failPendingLifecycle);
    } catch (Exception e) {
      log.warn("alert parse", e);
    }
  }

  private void failPendingLifecycle(String reason) {
    boolean failed = false;
    PendingTemplate template = pendingTemplate.getAndSet(null);
    if (template != null) {
      lifecycle.fail(reason);
      emitError(template.signal(), new IllegalStateException(reason), template.resolvedSignal(), template.swarmIdFallback());
      failed = true;
    }
    PendingStart start = pendingStart.getAndSet(null);
    if (start != null) {
      lifecycle.fail(reason);
      emitError(start.signal(), new IllegalStateException(reason), start.resolvedSignal(), start.swarmIdFallback());
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
      log.info("{} signal for swarm {}", label.substring(0, 1).toUpperCase() + label.substring(1), swarmId);
      action.apply(serializeArgs(cs));
      recordLifecycleState(resolvedSignal);
      if (ControlPlaneSignals.SWARM_TEMPLATE.equals(resolvedSignal)) {
        onTemplateSuccess(cs, resolvedSignal, swarmId);
      } else if (ControlPlaneSignals.SWARM_START.equals(resolvedSignal)) {
        onStartSuccess(cs, resolvedSignal, swarmId);
      } else {
        emitSuccess(cs, resolvedSignal, swarmId);
      }
    } catch (Exception e) {
      log.warn(label, e);
      emitError(cs, e, resolvedSignal, swarmId);
    }
  }

  private void onTemplateSuccess(ControlSignal cs, String resolvedSignal, String swarmId) {
    if (lifecycle.isReadyForWork()) {
      pendingTemplate.set(null);
      emitSuccess(cs, resolvedSignal, swarmId);
      return;
    }

    PendingTemplate newPending = new PendingTemplate(cs, resolvedSignal, swarmId);
    PendingTemplate previous = pendingTemplate.getAndSet(newPending);
    if (previous != null) {
      log.debug("Replacing pending swarm-template confirmation for correlation {} with {}", previous.signal().correlationId(), cs.correlationId());
    }
    tryEmitPendingTemplateReady();
  }

  private void tryEmitPendingTemplateReady() {
    while (true) {
      PendingTemplate pending = pendingTemplate.get();
      if (pending == null) {
        return;
      }
      if (!lifecycle.isReadyForWork()) {
        return;
      }
      if (pendingTemplate.compareAndSet(pending, null)) {
        emitSuccess(pending.signal(), pending.resolvedSignal(), pending.swarmIdFallback());
        return;
      }
    }
  }

  private void onStartSuccess(ControlSignal cs, String resolvedSignal, String swarmId) {
    if (!lifecycle.hasPendingConfigUpdates() && lifecycle.isReadyForWork()) {
      pendingStart.set(null);
      emitSuccess(cs, resolvedSignal, swarmId);
      return;
    }
    PendingStart newPending = new PendingStart(cs, resolvedSignal, swarmId);
    PendingStart previous = pendingStart.getAndSet(newPending);
    if (previous != null) {
      log.debug("Replacing pending swarm-start confirmation for correlation {} with {}",
          previous.signal().correlationId(), cs.correlationId());
    }
    tryEmitPendingStartReady();
  }

  private void tryEmitPendingStartReady() {
    while (true) {
      PendingStart pending = pendingStart.get();
      if (pending == null) {
        return;
      }
      if (lifecycle.hasPendingConfigUpdates() || !lifecycle.isReadyForWork()) {
        return;
      }
      if (pendingStart.compareAndSet(pending, null)) {
        emitSuccess(pending.signal(), pending.resolvedSignal(), pending.swarmIdFallback());
        return;
      }
    }
  }

  private void processConfigUpdate(ControlSignalEnvelope envelope, String rawPayload, String resolvedSignal) {
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
    if (rejectIfNotReady(cs, resolvedSignal, swarmIdOrDefault(cs), true)) {
      return;
    }

    JsonNode node = mapper.createObjectNode();
    if (cs.data() != null) {
      node = mapper.valueToTree(cs.data());
    }

    try {
      JsonNode dataNode = node;
      Boolean enabledFlag = dataNode.has("enabled") ? dataNode.path("enabled").asBoolean() : null;

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
            TrafficPolicy effectivePolicy = trafficPolicyFromSettings(updated);
            details.put("trafficPolicy", mapper.convertValue(effectivePolicy, Map.class));
          }
        }
      }

      List<Runnable> fanouts = new ArrayList<>();
      boolean controllerTarget = targetRole != null && this.role.equalsIgnoreCase(targetRole);

      if (controllerTarget) {
        if (enabledFlag != null) {
          lifecycle.setSwarmEnabled(enabledFlag);
          sendStatusDelta();
        }
        ScenarioChange change = applyScenarioOverrides(dataNode);
        scenarioChanged = scenarioChanged || change.changed();
        if (change.details() != null && !change.details().isEmpty()) {
          details.put("scenario", change.details());
        }
      } else if (roleAll) {
        // config-update signals for non-controller roles are routed directly to those roles/instances.
      }

      if (scenarioChanged) {
        sendStatusDelta();
      }
      CommandState state = configCommandState(details);
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
    SwarmStatus status = lifecycle.getStatus();
    boolean running = status == SwarmStatus.RUNNING;
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
    log.warn("Rejecting {} for swarm {} (initialized={}, ready={}, pendingConfigUpdates={}, status={})",
        resolvedSignal, swarmIdFallback, initialized, ready, pendingConfigUpdates,
        status != null ? status.name() : "unknown");
    Boolean enabled = null;
    if (ControlPlaneSignals.CONFIG_UPDATE.equals(resolvedSignal)) {
      enabled = status != null && workloadsEnabled(status);
    }
    CommandState state = new CommandState("NotReady", enabled, details);
    emitSuccess(cs, resolvedSignal, swarmIdFallback, state);
    return true;
  }

  private void recordLifecycleState(String resolvedSignal) {
    if (ControlPlaneSignals.SWARM_TEMPLATE.equals(resolvedSignal)) {
      templateApplied.set(true);
      return;
    }
    if (ControlPlaneSignals.SWARM_PLAN.equals(resolvedSignal)) {
      planApplied.set(true);
      return;
    }
    if (ControlPlaneSignals.SWARM_REMOVE.equals(resolvedSignal)) {
      templateApplied.set(false);
      planApplied.set(false);
    }
  }

  private boolean isInitialized() {
    return templateApplied.get() && planApplied.get();
  }

  private void handleSignal(ControlSignalEnvelope envelope, String rawPayload) {
    ControlSignal cs = envelope.signal();
    if (cs == null) {
      return;
    }
    String signal = resolveSignal(envelope);
    if (signal != null && !ControlPlaneSignals.STATUS_REQUEST.equals(signal)) {
      journal.append(SwarmJournalEntries.inSignal(mapper, envelope.routingKey(), cs));
    }
    switch (signal) {
      case ControlPlaneSignals.SWARM_TEMPLATE -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> lifecycle.prepare(args), "template");
        }
      }
      case ControlPlaneSignals.SWARM_PLAN -> {
        if (isForLocalSwarm(cs)) {
          String targetSwarm = swarmIdOrDefault(cs);
          log.info("Plan signal for swarm {} (origin={}, corr={}, idem={})",
              targetSwarm, cs.origin(), cs.correlationId(), cs.idempotencyKey());
          processSwarmSignal(cs, signal, targetSwarm,
              args -> lifecycle.applyScenarioPlan(args), "plan");
        }
      }
      case ControlPlaneSignals.SWARM_START -> {
        if (isForLocalSwarm(cs)) {
          if (rejectIfNotReady(cs, signal, swarmIdOrDefault(cs), false)) {
            return;
          }
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> {
            lifecycle.start(args);
            sendStatusFull();
          }, "start");
        }
      }
      case ControlPlaneSignals.SWARM_STOP -> {
        if (isForLocalSwarm(cs)) {
          if (rejectIfNotReady(cs, signal, swarmIdOrDefault(cs), true)) {
            return;
          }
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> {
            lifecycle.stop();
          }, "stop");
        }
      }
      case ControlPlaneSignals.SWARM_REMOVE -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> lifecycle.remove(), "remove");
        }
      }
      case ControlPlaneSignals.STATUS_REQUEST -> {
        log.debug("Status request received: {}", envelope.routingKey());
        sendStatusFull();
      }
      case ControlPlaneSignals.CONFIG_UPDATE -> processConfigUpdate(envelope, rawPayload, signal);
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
      return true;
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
    sendStatusDelta();
    tryEmitPendingStartReady();
  }

  private void emitSuccess(ControlSignal cs, String resolvedSignal, String swarmIdFallback) {
    emitSuccess(cs, resolvedSignal, swarmIdFallback, null);
  }

  private void emitSuccess(ControlSignal cs,
                           String resolvedSignal,
                           String swarmIdFallback,
                           CommandState overrideState) {
    String signal = requireSignal(confirmationSignal(cs, resolvedSignal), "Outcome");
    CommandState state = overrideState != null ? overrideState : stateForSuccess(signal);
    if (cs.idempotencyKey() == null || cs.idempotencyKey().isBlank()) {
      log.warn("Skipping outcome for {} due to missing idempotencyKey", signal);
      return;
    }
    io.pockethive.controlplane.messaging.ControlPlaneEmitter.ReadyContext context =
        io.pockethive.controlplane.messaging.ControlPlaneEmitter.ReadyContext.builder(
            signal,
            cs.correlationId(),
            cs.idempotencyKey(),
            state)
            .timestamp(Instant.now())
            .build();
    emitter.emitReady(context);
  }

  private void emitError(ControlSignal cs,
                         Exception e,
                         String resolvedSignal,
                         String swarmIdFallback) {
    String signal = requireSignal(confirmationSignal(cs, resolvedSignal), "Outcome");
    if (cs.idempotencyKey() == null || cs.idempotencyKey().isBlank()) {
      log.warn("Skipping error outcome for {} due to missing idempotencyKey", signal);
      return;
    }
    Boolean enabled = null;
    if (ControlPlaneSignals.CONFIG_UPDATE.equals(signal)) {
      SwarmStatus status = lifecycle.getStatus();
      enabled = status != null && workloadsEnabled(status);
    }
    CommandState baseState = new CommandState(null, enabled, null);
    String code = e.getClass().getSimpleName();
    String message = e.getMessage() == null || e.getMessage().isBlank() ? code : e.getMessage();
    io.pockethive.controlplane.messaging.ControlPlaneEmitter.ErrorContext.Builder builder =
        io.pockethive.controlplane.messaging.ControlPlaneEmitter.ErrorContext.builder(
            signal,
            cs.correlationId(),
            cs.idempotencyKey(),
            baseState,
            phaseForSignal(signal),
            code,
            message)
            .timestamp(Instant.now());
    emitter.emitError(builder.build());
  }

  private CommandState stateForSuccess(String signal) {
    return new CommandState(null, null, null);
  }

  private CommandState configCommandState(Map<String, Object> details) {
    Map<String, Object> detailCopy = (details == null || details.isEmpty()) ? null : new LinkedHashMap<>(details);
    SwarmStatus status = lifecycle.getStatus();
    boolean enabled = status != null && workloadsEnabled(status);
    return new CommandState(null, enabled, detailCopy);
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
    ControlSignal signal = envelope.signal();
    if (signal != null && signal.type() != null && !signal.type().isBlank()) {
      return signal.type();
    }
    RoutingKey key = ControlPlaneRouting.parseSignal(envelope.routingKey());
    return key != null ? defaultSegment(key.type(), null) : null;
  }

  private boolean isControllerCommand(ControlSignal cs) {
    io.pockethive.control.ControlScope scope = cs.scope();
    String role = scope != null ? scope.role() : null;
    if (role == null || role.isBlank() || isAllSegment(role)) {
      return false;
    }
    if (!this.role.equalsIgnoreCase(role)) {
      return false;
    }
    String targetInstance = scope != null ? scope.instance() : null;
    return targetInstance == null || targetInstance.isBlank() || isAllSegment(targetInstance)
        || instanceId.equalsIgnoreCase(targetInstance);
  }

  private record PendingTemplate(ControlSignal signal, String resolvedSignal, String swarmIdFallback) {}

  private record PendingStart(ControlSignal signal, String resolvedSignal, String swarmIdFallback) {}

  private String phaseForSignal(String signal) {
    if (signal == null || signal.isBlank()) {
      return signal;
    }
    return switch (signal) {
      case "swarm-template" -> "template";
      case "swarm-start" -> "start";
      case "swarm-stop" -> "stop";
      case "swarm-remove" -> "remove";
      case "config-update" -> "config-update";
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

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to serialize confirmation", ex);
    }
  }

  private void sendStatusFull() {
    SwarmMetrics m = lifecycle.getMetrics();
    String state = determineState(m);
    maybeJournalHealthTransition(state, m);
    SwarmStatus status = lifecycle.getStatus();
    boolean workloadsEnabled = workloadsEnabled(status);
    ConfirmationScope scope = ConfirmationScope.forInstance(swarmId, role, instanceId);
    String rk = ControlPlaneRouting.event("metric", "status-full", scope);
    StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder()
        .type("status-full")
        .role(role)
        .instance(instanceId)
        .origin(instanceId)
        .swarmId(swarmId)
        .workPlaneEnabled(false)
        .tpsEnabled(false)
        .filesystemEnabled(true)
        .enabled(workloadsEnabled)
        .state(state)
        .watermark(m.watermark())
        .maxStalenessSec(MAX_STALENESS_MS / 1000)
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", status.name())
        .data("startedAt", startedAt)
        .config(statusConfigSnapshot())
        .data("workers", workers.snapshot())
        .data("swarmDiagnostics", diagnostics.snapshot())
        .data("scenario", scenarioProgress());
    if (journalRunId != null) {
      builder.data("journal", Map.of("runId", journalRunId));
    }
    String controlQueue = properties.controlQueueName(role, instanceId);
    builder.controlIn(controlQueue)
        .controlRoutes(SwarmControllerRoutes.controllerControlRoutes(swarmId, role, instanceId))
        .controlOut(rk);
    appendTrafficDiagnostics(builder);
    String payload = builder.toJson();
    sendControl(rk, payload, "status");
  }

  private void sendStatusDelta() {
    SwarmMetrics m = lifecycle.getMetrics();
    String state = determineState(m);
    maybeJournalHealthTransition(state, m);
    SwarmStatus status = lifecycle.getStatus();
    boolean workloadsEnabled = workloadsEnabled(status);
    ConfirmationScope scope = ConfirmationScope.forInstance(swarmId, role, instanceId);
    String rk = ControlPlaneRouting.event("metric", "status-delta", scope);
    StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder()
        .type("status-delta")
        .role(role)
        .instance(instanceId)
        .origin(instanceId)
        .swarmId(swarmId)
        .workPlaneEnabled(false)
        .tpsEnabled(false)
        .enabled(workloadsEnabled)
        .state(state)
        .watermark(m.watermark())
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", status.name())
        .data("scenario", scenarioProgress());
    appendTrafficDiagnostics(builder);
    String payload = builder.toJson();
    sendControl(rk, payload, "status");
  }

  private Map<String, Map<String, Object>> toQueueStatsPayload(Map<String, QueueStats> snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      return Map.of();
    }
    Map<String, Map<String, Object>> payload = new LinkedHashMap<>();
    for (Map.Entry<String, QueueStats> entry : snapshot.entrySet()) {
      String queueName = entry.getKey();
      QueueStats stats = entry.getValue();
      if (queueName == null || queueName.isBlank() || stats == null) {
        continue;
      }
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("depth", stats.depth());
      values.put("consumers", stats.consumers());
      if (stats.oldestAgeSec() != null && stats.oldestAgeSec().isPresent()) {
        values.put("oldestAgeSec", stats.oldestAgeSec().getAsLong());
      }
      payload.put(queueName, values);
    }
    return payload;
  }

  private Map<String, Object> scenarioProgress() {
    Map<String, Object> snapshot = lifecycle.scenarioProgress();
    return snapshot != null ? snapshot : Map.of();
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

  private void appendTrafficDiagnostics(StatusEnvelopeBuilder builder) {
    if (builder == null) {
      return;
    }
    boolean guardActive = lifecycle.bufferGuardActive();
    String guardProblem = lifecycle.bufferGuardProblem();
    if (guardActive || guardProblem != null) {
      Map<String, Object> guardDiag = new LinkedHashMap<>();
      guardDiag.put("active", guardActive);
      if (guardProblem != null && !guardProblem.isBlank()) {
        guardDiag.put("problem", guardProblem);
      }
      builder.data("bufferGuard", guardDiag);
    }
  }

  private Map<String, Object> statusConfigSnapshot() {
    Map<String, Object> config = new LinkedHashMap<>();
    TrafficPolicy policy = effectiveTrafficPolicy();
    if (policy != null) {
      config.put("trafficPolicy", policy);
    }
    List<BufferGuardSettings> guards = lifecycle.bufferGuards();
    if (guards != null && !guards.isEmpty()) {
      config.put("bufferGuards", List.copyOf(guards));
    }
    return Map.copyOf(config);
  }

  private TrafficPolicy effectiveTrafficPolicy() {
    List<BufferGuardSettings> guards = lifecycle.bufferGuards();
    if (guards != null && !guards.isEmpty()) {
      BufferGuardSettings s = guards.get(0);
      return trafficPolicyFromSettings(s);
    }
    return lifecycle.trafficPolicy();
  }

  private TrafficPolicy trafficPolicyFromSettings(BufferGuardSettings s) {
    if (s == null) {
      return null;
    }
    BufferGuardPolicy.Adjustment adjust = new BufferGuardPolicy.Adjustment(
        s.adjust().maxIncreasePct(),
        s.adjust().maxDecreasePct(),
        s.adjust().minRatePerSec(),
        s.adjust().maxRatePerSec());
    BufferGuardPolicy.Prefill prefill = new BufferGuardPolicy.Prefill(
        s.prefill().enabled(),
        s.prefill().lookahead() != null ? s.prefill().lookahead().toString() : null,
        s.prefill().liftPct());
    BufferGuardPolicy.Backpressure backpressure = new BufferGuardPolicy.Backpressure(
        s.backpressure().queueAlias(),
        s.backpressure().highDepth(),
        s.backpressure().recoveryDepth(),
        s.backpressure().moderatorReductionPct());
    BufferGuardPolicy policy = new BufferGuardPolicy(
        Boolean.TRUE,
        s.queueAlias(),
        s.targetDepth(),
        s.minDepth(),
        s.maxDepth(),
        s.samplePeriod() != null ? s.samplePeriod().toString() : null,
        s.movingAverageWindow(),
        adjust,
        prefill,
        backpressure);
    return new TrafficPolicy(policy);
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


  private String determineState(SwarmMetrics m) {
    if (m.desired() > 0 && m.healthy() == 0) {
      return "Unknown";
    }
    if (m.healthy() < m.desired()) {
      return "Degraded";
    }
    return lifecycle.getStatus().name();
  }

  private void maybeJournalHealthTransition(String state, SwarmMetrics metrics) {
    // Journal health transitions only once the swarm is actually in a "workloads enabled" phase.
    //
    // Why: during initial boot / template apply, the controller knows the desired worker count
    // (desired>0) but workers have not yet had time to publish their first status heartbeat.
    // That makes the computed state look like Unknown/Degraded, which is expected and should not
    // spam the journal with false-positive "health degraded" entries.
    SwarmStatus status = lifecycle.getStatus();
    boolean enabled = workloadsEnabled(status);
    if (enabled && !healthWorkloadsEnabled) {
      healthWorkloadsEnabled = true;
      // On the edge of enabling workloads we expect a short period of 0 heartbeats / partial heartbeats.
      // Suppress journaling for a short window (aligned with MAX_STALENESS_MS) so operators don't see
      // a scary "Degraded" entry at the very start of a run.
      suppressHealthJournal();
    } else if (!enabled) {
      // Once disabled, we stop treating degraded states as actionable. Next time workloads are enabled
      // we'll re-arm the suppression window above.
      healthWorkloadsEnabled = false;
    }

    // Best-effort suppression window: keep status payloads flowing (state may still show Unknown/Degraded),
    // but avoid writing health-transition journal entries until the window elapses.
    Instant suppressUntil = this.healthJournalSuppressUntil;
    if (suppressUntil != null) {
      if (Instant.now().isBefore(suppressUntil)) {
        return;
      }
      this.healthJournalSuppressUntil = null;
    }
    String previous = this.lastHealthState;
    this.lastHealthState = state;
    if (previous != null && previous.equals(state)) {
      return;
    }
    boolean prevDegraded = "Degraded".equals(previous) || "Unknown".equals(previous);
    boolean currDegraded = "Degraded".equals(state) || "Unknown".equals(state);
    if (!prevDegraded && currDegraded) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("previousState", previous);
      data.put("currentState", state);
      data.put("desiredWorkers", metrics.desired());
      data.put("healthyWorkers", metrics.healthy());
      data.put("runningWorkers", metrics.running());
      data.put("enabledWorkers", metrics.enabled());
      journal.append(SwarmJournalEntries.local(
          swarmId,
          // WARN (not ERROR): health transitions are operator-facing signals, not necessarily application errors.
          "WARN",
          "swarm-health-degraded",
          instanceId,
          ControlScope.forInstance(swarmId, role, instanceId),
          Map.copyOf(data),
          null));
    } else if (prevDegraded && !currDegraded) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("previousState", previous);
      data.put("currentState", state);
      data.put("desiredWorkers", metrics.desired());
      data.put("healthyWorkers", metrics.healthy());
      data.put("runningWorkers", metrics.running());
      data.put("enabledWorkers", metrics.enabled());
      journal.append(SwarmJournalEntries.local(
          swarmId,
          "INFO",
          "swarm-health-recovered",
          instanceId,
          ControlScope.forInstance(swarmId, role, instanceId),
          Map.copyOf(data),
          null));
    }
  }

  private boolean workloadsEnabled(SwarmStatus status) {
    return status == SwarmStatus.RUNNING || status == SwarmStatus.STARTING;
  }

  private void suppressHealthJournal() {
    // Avoid false-positive "degraded/unknown" transitions immediately after workloads are enabled;
    // at this point workers have not had time to report their first heartbeat yet.
    this.healthJournalSuppressUntil = Instant.now().plusMillis(MAX_STALENESS_MS);
    this.lastHealthState = null;
  }

  private void sendControl(String routingKey, String payload, String context) {
    String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
    boolean statusLog = "status".equals(context) || (routingKey != null && routingKey.contains(".status-"));
    String snippet = snippet(payload);
    if (statusLog) {
      log.debug("[CTRL] {} rk={} inst={} payload={}", label, routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] {} rk={} inst={} payload={}", label, routingKey, instanceId, snippet);
    }
    if (routingKey != null && routingKey.startsWith("signal.")) {
      controlPlane.publishSignal(new SignalMessage(routingKey, payload));
    } else {
      controlPlane.publishEvent(new EventMessage(routingKey, payload));
    }
  }

  private void sendControl(String routingKey, String payload) {
    sendControl(routingKey, payload, null);
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
    return value != null && value.equalsIgnoreCase("ALL");
  }

  private String normaliseSwarmSegment(String value) {
    String resolved = defaultSegment(value, swarmId);
    if (isAllSegment(resolved)) {
      return swarmId;
    }
    return resolved;
  }

  private boolean isLocalSwarm(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return isAllSegment(value) || swarmId.equalsIgnoreCase(value);
  }
}
