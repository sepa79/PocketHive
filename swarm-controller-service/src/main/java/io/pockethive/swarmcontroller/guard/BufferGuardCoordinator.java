package io.pockethive.swarmcontroller.guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.manager.guard.BufferGuardMetrics;
import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.input.InputRateParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: integrate swarm-plan guard settings and lifecycle with the Manager SDK and Control Plane.
 * Must not: parse or repair source input rates, sample Rabbit directly or implement the feedback algorithm.
 * Contract: RESP-CONTROLLER-BUFFER-GUARD — docs/architecture/runtime-responsibilities.md#resp-controller-buffer-guard.
 * Consumes: RESP-WORK-INPUT-RATE — docs/architecture/runtime-responsibilities.md#resp-work-input-rate.
 */
public final class BufferGuardCoordinator {

  private static final Logger log = LoggerFactory.getLogger(BufferGuardCoordinator.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final SwarmControllerProperties properties;
  private final String swarmId;
  private final String instanceId;
  private final ControlPlanePublisher controlPublisher;
  private final ObjectMapper mapper;

  private final io.pockethive.manager.guard.BufferGuardCoordinator coordinator;
  private final Map<String, WorkerInputType> inputKindByRole = new HashMap<>();
  private volatile boolean active;
  private volatile String lastProblem;

  public BufferGuardCoordinator(SwarmControllerProperties properties,
                                QueueStatsPort queueStatsPort,
                                MeterRegistry meterRegistry,
                                ControlPlanePublisher controlPublisher,
                                ObjectMapper mapper,
                                String instanceId) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.swarmId = properties.getSwarmId();
    this.controlPublisher = Objects.requireNonNull(controlPublisher, "controlPublisher");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    this.coordinator = new io.pockethive.manager.guard.BufferGuardCoordinator(
        Objects.requireNonNull(queueStatsPort, "queueStatsPort"),
        settings -> {
          Tags tags = Tags.of("swarm", swarmId, "queue", settings.queueAlias());
          BufferGuardMetrics metrics = new MicrometerBufferGuardMetrics(meterRegistry, tags);
          return metrics;
        },
        (targetRole, rate) -> sendRateUpdate(targetRole, rate));
  }

  public void configureFromTemplate(String templateJson) {
    this.active = false;
    this.lastProblem = null;
    inputKindByRole.clear();
    if (!properties.getFeatures().bufferGuardEnabled()) {
      coordinator.configure(List.of());
      return;
    }
    try {
      SwarmPlan plan = mapper.readValue(templateJson, SwarmPlan.class);
      List<BufferGuardSettings> resolved = resolveSettings(plan);
      if (resolved.isEmpty()) {
        log.info("Buffer guard not configured: no eligible guard settings resolved for swarm {}", swarmId);
        coordinator.configure(List.of());
        return;
      }
      coordinator.configure(resolved);
      active = true;
      lastProblem = null;
    } catch (Exception ex) {
      log.warn("Failed to parse swarm plan for buffer guard configuration", ex);
      coordinator.configure(List.of());
      active = false;
      lastProblem = "plan-parse-error: " + ex.getClass().getSimpleName();
    }
  }

  public synchronized void onSwarmEnabled(boolean enabled) {
    coordinator.onEnabled(enabled);
  }

  public synchronized void onRemove() {
    coordinator.onRemove();
  }

  public synchronized void configure(java.util.List<BufferGuardSettings> settings) {
    coordinator.configure(settings);
  }

  public synchronized java.util.List<BufferGuardSettings> currentSettings() {
    return coordinator.currentSettings();
  }

  public boolean isActive() {
    return active;
  }

  public String lastProblem() {
    return lastProblem;
  }

  private void sendRateUpdate(String targetRole, double rate) {
    WorkerInputType kind = inputKindByRole.get(normalizeRole(targetRole));
    if (kind == null) {
      log.warn("Buffer guard attempted to update rate for role {} but no input mapping is configured; ignoring", targetRole);
      return;
    }
    var patch = mapper.createObjectNode();
    var inputs = patch.putObject("inputs");
    inputs.putObject(kind.settingsKey()).put(InputRateParser.FIELD, rate);
    try {
      var targetScope = io.pockethive.control.ControlScope.forRole(swarmId, targetRole);
      Map<String, Object> patchData = mapper.convertValue(patch, MAP_TYPE);
      var signal = io.pockethive.controlplane.messaging.ControlSignals.configUpdate(
          instanceId,
          targetScope,
          java.util.UUID.randomUUID().toString(),
          java.util.UUID.randomUUID().toString(),
	          patchData);
      String rk = ControlPlaneRouting.signal(
          ControlPlaneSignals.CONFIG_UPDATE, swarmId, targetRole, ControlScope.ALL);
      log.info("buffer-guard config-update rk={} correlationId={}", rk, signal.correlationId());
      controlPublisher.publishSignal(new SignalMessage(rk, signal));
    } catch (Exception ex) {
      log.warn("Failed to publish buffer-guard rate update for role {}", targetRole, ex);
    }
  }

  private List<BufferGuardSettings> resolveSettings(SwarmPlan plan) {
    TrafficPolicy policy = plan.trafficPolicy();
    if (policy == null) {
      lastProblem = "no-traffic-policy";
      return List.of();
    }
    List<BufferGuardSettings> result = new ArrayList<>();

    BufferGuardPolicy single = policy.bufferGuard();
    if (single != null) {
      buildSettings(plan, single).ifPresent(result::add);
    }
    List<BufferGuardPolicy> many = policy.bufferGuards();
    if (many != null) {
      for (BufferGuardPolicy guard : many) {
        if (guard != null) {
          buildSettings(plan, guard).ifPresent(result::add);
        }
      }
    }
    if (result.isEmpty()) {
      lastProblem = "no-guards-resolved";
    }
    return result;
  }

  private Optional<BufferGuardSettings> buildSettings(SwarmPlan plan, BufferGuardPolicy guard) {
    if (guard == null || !Boolean.TRUE.equals(guard.enabled())) {
      return Optional.empty();
    }

    String queueAlias = guard.queueAlias();
    if (!hasText(queueAlias)) {
      log.warn("Buffer guard enabled but queueAlias missing; guard configuration ignored");
      lastProblem = "missing-queue-alias";
      return Optional.empty();
    }
    String queueName;
    try {
      queueName = properties.queueName(queueAlias);
    } catch (IllegalArgumentException ex) {
      log.warn("Buffer guard queue alias '{}' invalid: {}", queueAlias, ex.getMessage());
      lastProblem = "invalid-queue-alias";
      return Optional.empty();
    }

    if (plan.bees() == null) {
      log.warn("Buffer guard requires at least one bee to determine target role");
      lastProblem = "no-bees";
      return Optional.empty();
    }
    Bee targetBee = plan.bees().stream()
        .filter(bee -> bee.work() != null && hasQueueAlias(bee.work().out(), queueAlias))
        .findFirst()
        .orElse(null);
    String targetRole = targetBee != null ? targetBee.role() : null;
    if (!hasText(targetRole)) {
      log.warn("Buffer guard could not find a producer role for queue '{}'", queueAlias);
      lastProblem = "missing-producer";
      return Optional.empty();
    }

    WorkerInputType inputKind = resolveInputKind(targetBee.config());
    if (inputKind != null) {
      inputKindByRole.put(normalizeRole(targetRole), inputKind);
    } else {
      log.info("No rate-controllable input configured for role {}; buffer guard will use adjustment bounds only", targetRole);
      lastProblem = "no-rate-input";
    }

    int targetDepth = defaultInt(guard.targetDepth(), 200);
    int minDepth = defaultInt(guard.minDepth(), 150);
    int maxDepth = defaultInt(guard.maxDepth(), 260);
    Duration samplePeriod = parseDuration(guard.samplePeriod(), Duration.ofSeconds(5));
    int movingAverageWindow = defaultInt(guard.movingAverageWindow(), 4);

    BufferGuardPolicy.Adjustment adjustPolicy = guard.adjust();
    BufferGuardSettings.Adjustment adjustment = new BufferGuardSettings.Adjustment(
        defaultInt(adjustPolicy != null ? adjustPolicy.maxIncreasePct() : null, 20),
        defaultInt(adjustPolicy != null ? adjustPolicy.maxDecreasePct() : null, 10),
        defaultInt(adjustPolicy != null ? adjustPolicy.minRatePerSec() : null, 1),
        defaultInt(adjustPolicy != null ? adjustPolicy.maxRatePerSec() : null, 100));

    BufferGuardPolicy.Prefill prefillPolicy = guard.prefill();
    boolean prefillEnabled = prefillPolicy != null && Boolean.TRUE.equals(prefillPolicy.enabled());
    Duration lookahead = parseDuration(prefillPolicy != null ? prefillPolicy.lookahead() : null, Duration.ofMinutes(2));
    int liftPct = Math.max(0, defaultInt(prefillPolicy != null ? prefillPolicy.liftPct() : null, 20));
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
    int highDepth = defaultInt(bpPolicy != null ? bpPolicy.highDepth() : null, 500);
    int recoveryDepth = defaultInt(bpPolicy != null ? bpPolicy.recoveryDepth() : null, 250);
    if (recoveryDepth > highDepth) {
      recoveryDepth = highDepth;
    }
    BufferGuardSettings.Backpressure backpressure = new BufferGuardSettings.Backpressure(
        downstreamAlias,
        downstreamQueue,
        highDepth,
        recoveryDepth,
        defaultInt(bpPolicy != null ? bpPolicy.moderatorReductionPct() : null, 15));

    double initialRate = inputKind == null
        ? adjustment.minRatePerSec()
        : configuredInputRate(targetBee.config(), inputKind);
    initialRate = clampRate(initialRate, adjustment);

    return Optional.of(new BufferGuardSettings(
        queueAlias,
        queueName,
        targetRole,
        initialRate,
        targetDepth,
        minDepth,
        maxDepth,
        samplePeriod,
        movingAverageWindow,
        adjustment,
        prefill,
        backpressure));
  }

  private double configuredInputRate(Map<String, Object> config, WorkerInputType kind) {
    Object inputs = config.get("inputs");
    Object settings = inputs instanceof Map<?, ?> values ? values.get(kind.settingsKey()) : null;
    Object rate = settings instanceof Map<?, ?> values ? values.get(InputRateParser.FIELD) : null;
    return new InputRateParser().parse(rate, InputRateParser.PATHS_BY_INPUT.get(kind.name()));
  }

  private static double clampRate(double candidate, BufferGuardSettings.Adjustment adjustment) {
    double min = Math.max(0d, adjustment.minRatePerSec());
    double max = Math.max(min, adjustment.maxRatePerSec());
    return Math.max(min, Math.min(max, candidate));
  }

  private static int defaultInt(Integer candidate, int fallback) {
    return candidate != null ? candidate : fallback;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean hasQueueAlias(Map<String, String> ports, String queueAlias) {
    if (ports == null || ports.isEmpty() || queueAlias == null || queueAlias.isBlank()) {
      return false;
    }
    for (String value : ports.values()) {
      if (value != null && queueAlias.equalsIgnoreCase(value.trim())) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeRole(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static WorkerInputType resolveInputKind(Map<String, Object> config) {
    if (config == null || config.isEmpty()) {
      return null;
    }
    Object inputsObj = config.get("inputs");
    if (!(inputsObj instanceof Map<?, ?> inputsMap)) {
      return null;
    }
    Object typeObj = inputsMap.get("type");
    if (!(typeObj instanceof String rawType)) {
      return null;
    }
    String normalized = rawType.trim().toUpperCase(Locale.ROOT);
    for (WorkerInputType kind : List.of(WorkerInputType.SCHEDULER, WorkerInputType.REDIS_DATASET)) {
      if (kind.name().equals(normalized)) {
        return kind;
      }
    }
    return null;
  }

  private static Duration parseDuration(String candidate, Duration fallback) {
    if (candidate == null || candidate.isBlank()) {
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
      log.warn("Unable to parse buffer guard duration '{}' ({}); using fallback {}", candidate, ex.getMessage(), fallback);
      return fallback;
    }
  }
}
