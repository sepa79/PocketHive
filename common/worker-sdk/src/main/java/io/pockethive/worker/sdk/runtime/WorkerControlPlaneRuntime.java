package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.Alerts;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.RedisSequenceConfiguration;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.config.ConfigKeyCanonicalizer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integrates the worker runtime with the control-plane helper so configuration updates, status
 * requests, and confirmation events are handled consistently across worker services. Usage guidance
 * lives in {@code docs/sdk/worker-sdk-quickstart.md}.
 */
public final class WorkerControlPlaneRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkerControlPlaneRuntime.class);

    private final WorkerControlPlane workerControlPlane;
    private final WorkerStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final ControlPlaneEmitter emitter;
    private final ControlPlaneIdentity identity;
    private final String controlQueueName;
    private final String[] controlRoutes;
    private final ConfigMerger configMerger;
    private final WorkerSignalListener signalListener = new WorkerSignalDispatcher();
    private final Map<String, List<Consumer<WorkerStateSnapshot>>> stateListeners = new ConcurrentHashMap<>();
    private final List<Consumer<WorkerStateSnapshot>> globalStateListeners = new CopyOnWriteArrayList<>();
    private final ControlPlaneNotifier notifier;
    private final TemplateRenderer templateRenderer;

    /**
     * Tracks the most recent status-delta emission so we can derive a per-second throughput
     * rather than reporting raw interval counts. Snapshots reuse the last computed values.
     */
    private volatile long lastDeltaStatusAtMillis = 0L;
    private volatile double lastComputedTps = 0.0;
    private volatile double lastIntervalSeconds = 0.0;
    private final Instant startedAt;
    private final Map<String, Object> runtimeMeta;

    private static final List<String> IO_INPUT_PRECEDENCE = List.of(
        "upstream-error",
        "out-of-data",
        "backpressure",
        "ok",
        "unknown"
    );

    private static final List<String> IO_OUTPUT_PRECEDENCE = List.of(
        "downstream-error",
        "blocked",
        "throttled",
        "ok",
        "unknown"
    );

    private final AtomicReference<String> lastWorkInputState = new AtomicReference<>(null);

    public WorkerControlPlaneRuntime(
        WorkerControlPlane workerControlPlane,
        WorkerStateStore stateStore,
        ObjectMapper objectMapper,
        ControlPlaneEmitter emitter,
        ControlPlaneIdentity identity,
        WorkerControlPlaneProperties.ControlPlane controlPlane
    ) {
        this(workerControlPlane, stateStore, objectMapper, emitter, identity, controlPlane, null);
    }

    public WorkerControlPlaneRuntime(
        WorkerControlPlane workerControlPlane,
        WorkerStateStore stateStore,
        ObjectMapper objectMapper,
        ControlPlaneEmitter emitter,
        ControlPlaneIdentity identity,
        WorkerControlPlaneProperties.ControlPlane controlPlane,
        TemplateRenderer templateRenderer
    ) {
        this.workerControlPlane = Objects.requireNonNull(workerControlPlane, "workerControlPlane");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.configMerger = new ConfigMerger(this.objectMapper);
        this.templateRenderer = templateRenderer;
        this.runtimeMeta = buildRuntimeMeta();
        WorkerControlPlaneProperties.ControlPlane resolvedControlPlane =
            Objects.requireNonNull(controlPlane, "controlPlane");
        this.controlQueueName = resolvedControlPlane.getControlQueueName();
        this.controlRoutes = resolveControlRoutes(resolvedControlPlane.getRoutes(), identity);
        this.notifier = new ControlPlaneNotifier(
            log,
            this.objectMapper,
            emitter,
            identity.role(),
            identity.instanceId()
        );
        this.startedAt = Instant.now();
        // Ensure workers discovered during runtime bootstrap receive a status publisher.
        stateStore.all().forEach(this::ensureStatusPublisher);
    }

    /**
     * Returns the most recent typed configuration applied to the given worker bean, if available.
     */
    public <C> Optional<C> workerConfig(String workerBeanName, Class<C> type) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        Objects.requireNonNull(type, "type");
        return stateStore.find(workerBeanName).flatMap(state -> state.config(type));
    }

    /**
     * Returns the latest raw configuration map applied to the worker bean, or an empty map when missing.
     */
    public Map<String, Object> workerRawConfig(String workerBeanName) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        return stateStore.find(workerBeanName)
            .map(WorkerState::rawConfig)
            .orElse(Map.of());
    }

    /**
     * Returns a {@link StatusPublisher} for the given worker bean, creating one if necessary.
     * <p>
     * Intended for infrastructure components (inputs/outputs) that need to surface diagnostics
     * in the worker status payload without reaching into worker state internals directly.
     */
    public StatusPublisher statusPublisher(String workerBeanName) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        WorkerState state = stateStore.find(workerBeanName).orElse(null);
        if (state == null) {
            throw new IllegalArgumentException("Unknown worker bean: " + workerBeanName);
        }
        return ensureStatusPublisher(state);
    }

    /**
     * Returns the last known enablement flag for the worker bean (defaults to {@code true} when no command has been applied yet).
     */
    public boolean workerEnabled(String workerBeanName) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        return stateStore.find(workerBeanName)
            .map(WorkerState::enabled)
            .orElse(true);
    }

    /**
     * Returns a snapshot of the latest status data emitted by the worker bean.
     */
    public Map<String, Object> workerStatusData(String workerBeanName) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        return stateStore.find(workerBeanName)
            .map(state -> state.statusData().isEmpty() ? Map.<String, Object>of() : state.statusData())
            .orElse(Map.of());
    }

    /**
     * Publish a control-plane alert (kind=event,type=alert) for an exception thrown while
     * processing a work item.
     * <p>
     * Swarm journal projections will pick this up via the swarm-controller alert listener,
     * making worker failures visible in Hive UI without introducing new contracts.
     */
    public void publishWorkError(String workerBeanName, WorkItem workItem, Throwable exception) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        Objects.requireNonNull(workItem, "workItem");
        Objects.requireNonNull(exception, "exception");

        String correlationId = null;
        String idempotencyKey = null;
        Object correlationHeader = workItem.headers().get("correlationId");
        if (correlationHeader != null) {
            correlationId = String.valueOf(correlationHeader);
        }
        Object idempotencyHeader = workItem.headers().get("idempotencyKey");
        if (idempotencyHeader != null) {
            idempotencyKey = String.valueOf(idempotencyHeader);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("worker", workerBeanName);

        if (workItem.messageId() != null) {
            context.put("messageId", workItem.messageId());
        }
        Object callId = workItem.headers().get("x-ph-call-id");
        if (callId != null) {
            context.put("callId", String.valueOf(callId));
        }
        workItem.observabilityContext().ifPresent(obs -> {
            if (obs.getTraceId() != null && !obs.getTraceId().isBlank()) {
                context.put("traceId", obs.getTraceId());
            }
        });

        ControlScope scope = ControlScope.forInstance(identity.swarmId(), identity.role(), identity.instanceId());
        emitter.publishAlert(Alerts.fromException(
            identity.instanceId(),
            scope,
            correlationId,
            idempotencyKey,
            runtimeMeta,
            "work",
            exception,
            null,
            context,
            Instant.now()
        ));
    }

    /**
     * Publish a control-plane outcome (kind=event,type=outcome) for non-error worker journal entries.
     * This is intended for informational lifecycle events that must be visible in journal projections
     * without polluting alert channels.
     */
    public void publishWorkJournalEvent(String workerBeanName,
                                        String correlationId,
                                        String idempotencyKey,
                                        String signal,
                                        String status,
                                        String callId,
                                        String messageId,
                                        String traceId,
                                        Map<String, Object> details) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        String journalCorrelationId = requireNonBlank(correlationId, "correlationId");
        String journalSignal = requireNonBlank(signal, "signal");
        String journalStatus = requireNonBlank(status, "status");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("worker", workerBeanName);

        String normalizedMessageId = normalizeBlank(messageId);
        if (normalizedMessageId != null) {
            context.put("messageId", normalizedMessageId);
        }
        String normalizedCallId = normalizeBlank(callId);
        if (normalizedCallId != null) {
            context.put("callId", normalizedCallId);
        }
        if (details != null && !details.isEmpty()) {
            context.putAll(details);
        }
        String normalizedTraceId = normalizeBlank(traceId);
        if (normalizedTraceId != null) {
            context.put("traceId", normalizedTraceId);
        }

        emitter.emitReady(ControlPlaneEmitter.ReadyContext.builder(
                journalSignal,
                journalCorrelationId,
                normalizeBlank(idempotencyKey),
                io.pockethive.control.CommandState.status(journalStatus))
            .details(context)
            .build());
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Registers a listener that will be invoked whenever the specified worker's state changes. The listener is
     * invoked immediately with the current snapshot if the worker is known.
     */
    public void registerStateListener(String workerBeanName, Consumer<WorkerStateSnapshot> listener) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        Objects.requireNonNull(listener, "listener");
        WorkerState state = stateStore.find(workerBeanName).orElse(null);
        stateListeners.computeIfAbsent(workerBeanName, key -> new CopyOnWriteArrayList<>()).add(listener);
        if (state != null) {
            safeInvoke(listener, new WorkerStateSnapshot(state, snapshotRawConfig(state)));
        }
    }

    /**
     * Seeds the worker state with the provided default configuration if no control-plane override has been applied yet.
     */
    public void registerDefaultConfig(String workerBeanName, Object defaultConfig) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        if (defaultConfig == null) {
            return;
        }
        WorkerState state = stateStore.find(workerBeanName).orElse(null);
        if (state == null) {
            log.warn("Unable to seed default config for unknown worker {}", workerBeanName);
            return;
        }
        Map<String, Object> rawConfig = configMerger.toRawConfig(defaultConfig);
        Boolean enabled = null;
        Object typedConfig = ensureTypedDefault(state.definition(), defaultConfig, rawConfig);
        if (state.seedConfig(typedConfig, enabled)) {
            state.updateRawConfig(rawConfig);
            RedisSequenceConfiguration.configureFromWorkerConfig(rawConfig);
            ensureStatusPublisher(state);
            notifier.logInitialConfig(state, rawConfig, enabled);
            notifyStateListeners(state);
        }
    }

    /**
     * Registers a listener that will be notified for every worker state change. Existing worker snapshots are
     * delivered immediately upon registration.
     */
    public void registerGlobalStateListener(Consumer<WorkerStateSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        globalStateListeners.add(listener);
        stateStore.all().forEach(state -> safeInvoke(listener, new WorkerStateSnapshot(state, snapshotRawConfig(state))));
    }

    /**
     * Consumes a raw control-plane payload. Returns {@code true} if the payload was handled by the
     * control-plane helper, {@code false} if it was filtered or ignored.
     */
    public boolean handle(String payload, String routingKey) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(routingKey, "routingKey");
        return workerControlPlane.consume(payload, routingKey, signalListener);
    }

    /**
     * Emits a full status snapshot using the accumulated worker state.
     */
    public void emitStatusSnapshot() {
        emitStatus(true);
    }

    /**
     * Emits a delta status event, draining processed counters for the interval.
     */
    public void emitStatusDelta() {
        emitStatus(false);
    }

    private final class WorkerSignalDispatcher implements WorkerSignalListener {

        @Override
        public void onConfigUpdate(WorkerConfigCommand command) {
            try {
                handleConfigUpdate(command);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new RuntimeException("Failed to process config-update", ex);
            }
        }

        @Override
        public void onStatusRequest(WorkerStatusRequest request) {
            log.debug("Received status-request signal {} => emitting snapshot", resolveSignalName(request));
            emitStatusSnapshot();
        }

        @Override
        public void onUnsupported(WorkerSignalContext context) {
            log.debug("Ignoring unsupported control-plane signal: {}", context.envelope().signal().type());
        }
    }

    private void handleConfigUpdate(WorkerConfigCommand command) {
        ControlSignal signal = command.signal();
        List<WorkerState> targets = resolveTargets(command);
        if (targets.isEmpty()) {
            log.warn("No worker definitions matched config-update signal role={} instance={}",
                signal.scope() != null ? signal.scope().role() : null,
                signal.scope() != null ? signal.scope().instance() : null);
            return;
        }
        Map<String, Object> sanitized = sanitiseConfig(command.data());
        for (WorkerState state : targets) {
            ensureStatusPublisher(state);
            WorkerConfigPatch patch = workerConfigFor(state, sanitized);
            FilteredConfigUpdate filtered = preprocessConfigUpdate(patch.values());
            if (filtered.reseedRequested() && templateRenderer != null) {
                templateRenderer.resetSeededSelections();
            }
            Map<String, Object> filteredUpdate = filtered.values();
            Map<String, Object> canonicalUpdate = ConfigKeyCanonicalizer.canonicalise(filteredUpdate);
            boolean previousEnabled = state.enabled();
            try {
                ConfigMerger.ConfigMergeResult mergeResult = configMerger.merge(
                    state.definition(),
                    state.rawConfig(),
                    canonicalUpdate,
                    patch.resetRequested()
                );
                Boolean enabled = command.enabled();
                if (log.isDebugEnabled()) {
                    log.debug("Applying config-update for worker={} role={} previousEnabled={} requestedEnabled={} data={}",
                        state.definition().beanName(),
                        state.definition().role(),
                        previousEnabled,
                        enabled,
                        canonicalUpdate);
                }
                state.updateConfig(mergeResult.typedConfig(), mergeResult.replaced(), enabled);
                state.updateRawConfig(mergeResult.rawConfig());
                RedisSequenceConfiguration.configureFromWorkerConfig(mergeResult.rawConfig());
                Map<String, Object> appliedConfig = mergeResult.replaced() ? mergeResult.rawConfig() : Map.of();
                if (hasCorrelation(signal)) {
                    notifier.emitConfigReady(signal, state, appliedConfig);
                } else {
                    log.warn(
                        "Skipping ready confirmation for signal {} due to missing correlation/idempotency",
                        signal.type()
                    );
                }
                notifyStateListeners(state);
                Map<String, Object> finalConfig = mergeResult.replaced()
                    ? mergeResult.rawConfig()
                    : mergeResult.previousRaw();
                boolean finalEnabled = state.enabled();
                if (log.isDebugEnabled()) {
                    log.debug("Applied config-update for worker={} role={} finalEnabled={} configKeys={}",
                        state.definition().beanName(),
                        state.definition().role(),
                        finalEnabled,
                        finalConfig.keySet());
                }
                if (shouldLogConfigUpdate(
                    patch,
                    command,
                    mergeResult.previousRaw(),
                    finalConfig,
                    previousEnabled,
                    finalEnabled
                )) {
                    notifier.logConfigUpdate(
                        signal,
                        state,
                        mergeResult.diff(),
                        finalConfig,
                        previousEnabled,
                        finalEnabled
                    );
                }
            } catch (Exception ex) {
                if (hasCorrelation(signal)) {
                    notifier.emitConfigError(signal, state, ex);
                } else {
                    log.warn(
                        "Skipping error confirmation for signal {} due to missing correlation/idempotency",
                        signal.type()
                    );
                }
                notifyStateListeners(state);
                log.warn("Failed to apply config update for worker {}", state.definition().beanName(), ex);
            }
        }
        emitStatusSnapshot();
    }

    private FilteredConfigUpdate preprocessConfigUpdate(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new FilteredConfigUpdate(Map.of(), false);
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                filtered.put(key, value);
            }
        });
        boolean reseedRequested = false;
        Object templating = filtered.get("templating");
        if (templating instanceof Map<?, ?> map) {
            Object reseedValue = map.get("reseed");
            reseedRequested = isTruthy(reseedValue);
            if (reseedRequested) {
                Map<String, Object> nested = new LinkedHashMap<>(toStringMap(map));
                nested.remove("reseed");
                if (nested.isEmpty()) {
                    filtered.remove("templating");
                } else {
                    filtered.put("templating", Map.copyOf(nested));
                }
            }
        }
        if (filtered.isEmpty()) {
            return new FilteredConfigUpdate(Map.of(), reseedRequested);
        }
        return new FilteredConfigUpdate(Map.copyOf(filtered), reseedRequested);
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            return "true".equalsIgnoreCase(trimmed);
        }
        return false;
    }

    private record FilteredConfigUpdate(Map<String, Object> values, boolean reseedRequested) { }

    private String resolveSignalName(WorkerStatusRequest request) {
        ControlSignal signal = request.signal();
        if (signal != null && signal.type() != null && !signal.type().isBlank()) {
            return signal.type();
        }
        ControlPlaneRouting.RoutingKey routingKey = ControlPlaneRouting.parseSignal(request.envelope().routingKey());
        if (routingKey != null && routingKey.type() != null && !routingKey.type().isBlank()) {
            return routingKey.type();
        }
        return "n/a";
    }
    private Object ensureTypedDefault(WorkerDefinition definition, Object defaultConfig, Map<String, Object> rawConfig) {
        Class<?> configType = definition.configType();
        if (configType == Void.class) {
            return null;
        }
        if (defaultConfig != null && configType.isInstance(defaultConfig)) {
            return defaultConfig;
        }
        if (rawConfig == null || rawConfig.isEmpty()) {
            return null;
        }
        return configMerger.toTypedConfig(definition, rawConfig);
    }

    private boolean hasCorrelation(ControlSignal signal) {
        return signal.correlationId() != null && !signal.correlationId().isBlank()
            && signal.idempotencyKey() != null && !signal.idempotencyKey().isBlank();
    }

    private List<WorkerState> resolveTargets(WorkerConfigCommand command) {
        String requestedWorker = findWorkerIdentifier(command);
        if (requestedWorker != null) {
            return stateStore.find(requestedWorker).map(List::of).orElseGet(List::of);
        }
        ControlSignal signal = command.signal();
        String role = normaliseRole(signal.scope() != null ? signal.scope().role() : null);
        List<WorkerState> matches = new ArrayList<>();
        for (WorkerState state : stateStore.all()) {
            WorkerDefinition definition = state.definition();
            if (!roleMatches(role, definition.role())) {
                continue;
            }
            matches.add(state);
        }
        return matches;
    }

    private String findWorkerIdentifier(WorkerConfigCommand command) {
        Map<String, Object> arguments = command.arguments();
        Map<String, Object> data = command.data();
        return firstText(arguments, "worker", "workerBean", "bean", "target")
            .or(() -> firstText(data, "worker", "workerBean", "bean", "target"))
            .orElse(null);
    }

    private Optional<String> firstText(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys.length == 0) {
            return Optional.empty();
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return Optional.of(text.trim());
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> sanitiseConfig(Map<String, Object> rawData) {
        if (rawData == null || rawData.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(rawData);
        sanitized.remove("worker");
        sanitized.remove("workerBean");
        sanitized.remove("bean");
        sanitized.remove("target");
        return Collections.unmodifiableMap(sanitized);
    }

    private WorkerConfigPatch workerConfigFor(WorkerState state, Map<String, Object> sanitized) {
        if (sanitized.isEmpty()) {
            return WorkerConfigPatch.empty();
        }
        String beanName = state.definition().beanName();
        Object workersSection = sanitized.get("workers");
        if (workersSection instanceof Map<?, ?> workersMap) {
            if (!workersMap.containsKey(beanName)) {
                return WorkerConfigPatch.empty();
            }
            Object candidate = workersMap.get(beanName);
            if (candidate instanceof Map<?, ?> nested) {
                Map<String, Object> copied = toStringMap(nested);
                boolean resetRequested = copied.isEmpty();
                return new WorkerConfigPatch(copied, true, resetRequested);
            }
            if (candidate == null) {
                return new WorkerConfigPatch(Map.of(), true, true);
            }
        }
        Object direct = sanitized.get(beanName);
        if (direct instanceof Map<?, ?> nested) {
            Map<String, Object> copied = toStringMap(nested);
            boolean resetRequested = copied.isEmpty();
            return new WorkerConfigPatch(copied, true, resetRequested);
        }
        if (direct == null && sanitized.containsKey(beanName)) {
            return new WorkerConfigPatch(Map.of(), true, true);
        }
        return new WorkerConfigPatch(sanitized, false, false);
    }

    private Map<String, Object> snapshotRawConfig(WorkerState state) {
        return state.rawConfig();
    }

    private boolean shouldLogConfigUpdate(
        WorkerConfigPatch patch,
        WorkerConfigCommand command,
        Map<String, Object> previousConfig,
        Map<String, Object> finalConfig,
        Boolean previousEnabled,
        Boolean finalEnabled
    ) {
        if (patch.hasPayload() || command.enabled() != null) {
            return true;
        }
        if (!Objects.equals(previousEnabled, finalEnabled)) {
            return true;
        }
        return !Objects.equals(previousConfig, finalConfig);
    }

    private record WorkerConfigPatch(Map<String, Object> values, boolean targeted, boolean resetRequested) {

        static WorkerConfigPatch empty() {
            return new WorkerConfigPatch(Map.of(), false, false);
        }

        boolean hasPayload() {
            return resetRequested || (values != null && !values.isEmpty());
        }
    }

    private Map<String, Object> toStringMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(key.toString(), value);
            }
        });
        return Map.copyOf(copy);
    }

    private boolean roleMatches(String signalRole, String workerRole) {
        if (signalRole == null || ControlScope.ALL.equalsIgnoreCase(signalRole)) {
            return true;
        }
        return signalRole.equalsIgnoreCase(workerRole);
    }

    private String normaliseRole(String role) {
        if (role == null) {
            return ControlScope.ALL;
        }
        String trimmed = role.trim();
        if (trimmed.isEmpty()) {
            return ControlScope.ALL;
        }
        if (ControlScope.ALL.equalsIgnoreCase(trimmed)) {
            return ControlScope.ALL;
        }
        return trimmed;
    }

    private void emitStatus(boolean snapshot) {
        List<WorkerState> states = new ArrayList<>(stateStore.all());
        if (states.isEmpty()) {
            log.debug("Skipping status emission; no worker states registered");
            return;
        }

        StatusSnapshot snapshotData = collectSnapshot(states, snapshot);
        Map<String, Object> configSnapshot = snapshot ? statusConfigSnapshot(states) : Map.of();

        IoStateAggregate workerIo = ioStateFromWorkers(states);
        IoStateAggregate ioStateForEnvelope = workerIo != null
            ? new IoStateAggregate(
                Objects.requireNonNullElse(workerIo.workInput(), "unknown"),
                Objects.requireNonNullElse(workerIo.workOutput(), "unknown"),
                workerIo.workContext())
            : new IoStateAggregate("unknown", "unknown", null);

        long nowMillis = System.currentTimeMillis();
        double intervalSeconds;
        double tps;
        if (snapshot) {
            intervalSeconds = lastIntervalSeconds;
            tps = lastComputedTps;
        } else {
            long previous = lastDeltaStatusAtMillis;
            lastDeltaStatusAtMillis = nowMillis;
            long intervalMillis = previous > 0L ? Math.max(1L, nowMillis - previous) : 0L;
            intervalSeconds = intervalMillis > 0L ? intervalMillis / 1000.0 : 0.0;
            if (intervalSeconds > 0.0) {
                tps = snapshotData.processedTotal() / intervalSeconds;
            } else {
                tps = 0.0;
            }
            lastComputedTps = tps;
            lastIntervalSeconds = intervalSeconds;
        }

        Consumer<io.pockethive.observability.StatusEnvelopeBuilder> customiser = builder -> {
            builder.role(identity.role())
                .instance(identity.instanceId())
                .swarmId(identity.swarmId())
                .enabled(snapshotData.enabled());

            if (controlQueueName != null) {
                builder.controlIn(controlQueueName);
            }
            if (controlRoutes.length > 0) {
                builder.controlRoutes(controlRoutes);
            }
            if (!snapshotData.workIn().isEmpty()) {
                builder.workIn(snapshotData.workIn().toArray(String[]::new));
            }
            if (!snapshotData.workOut().isEmpty()) {
                builder.workOut(snapshotData.workOut().toArray(String[]::new));
            }

            builder.ioWorkState(ioStateForEnvelope.workInput(), ioStateForEnvelope.workOutput(), ioStateForEnvelope.workContext());
            if (snapshot) {
                builder.filesystemEnabled(true);
            }

            builder.tps(Math.round(tps));
            builder.data("intervalSeconds", intervalSeconds);
            if (snapshot) {
                builder.data("startedAt", startedAt);
                builder.config(configSnapshot);
            }
        };

        maybeEmitIoOutOfData(workerIo);

        ControlPlaneEmitter.StatusContext statusContext = ControlPlaneEmitter.StatusContext.of(customiser);
        if (snapshot) {
            emitter.emitStatusSnapshot(statusContext);
        } else {
            emitter.emitStatusDelta(statusContext);
        }
    }

    private IoStateAggregate ioStateFromWorkers(List<WorkerState> states) {
        if (states == null || states.isEmpty()) {
            return null;
        }
        String bestInput = null;
        String bestOutput = null;
        Map<String, Object> bestContext = null;
        for (WorkerState state : states) {
            Map<String, Object> status = state.statusData();
            if (status == null || status.isEmpty()) {
                continue;
            }
            Object ioState = status.get("ioState");
            if (!(ioState instanceof Map<?, ?> ioMapRaw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ioMap = (Map<String, Object>) ioMapRaw;
            Object workObj = ioMap.get("work");
            if (!(workObj instanceof Map<?, ?> workRaw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> work = (Map<String, Object>) workRaw;
            String input = asIoState(work.get("input"), IO_INPUT_PRECEDENCE);
            String output = asIoState(work.get("output"), IO_OUTPUT_PRECEDENCE);
            if (input != null && isBetter(input, bestInput, IO_INPUT_PRECEDENCE)) {
                bestInput = input;
                bestContext = contextMap(work.get("context"));
            }
            if (output != null && isBetter(output, bestOutput, IO_OUTPUT_PRECEDENCE)) {
                bestOutput = output;
            }
        }
        if (bestInput == null && bestOutput == null) {
            return null;
        }
        return new IoStateAggregate(bestInput, bestOutput, bestContext);
    }

    private void maybeEmitIoOutOfData(IoStateAggregate aggregate) {
        if (aggregate == null) {
            return;
        }
        String current = aggregate.workInput();
        String previous = lastWorkInputState.getAndSet(current);
        if ("out-of-data".equals(previous) || !"out-of-data".equals(current)) {
            return;
        }
        String dataset = null;
        String logRef = null;
        Map<String, Object> context = aggregate.workContext();
        if (context != null) {
            Object rawDataset = context.get("dataset");
            if (rawDataset instanceof String s && !s.isBlank()) {
                dataset = s.trim();
            }
            Object rawLogRef = context.get("logRef");
            if (rawLogRef instanceof String s && !s.isBlank()) {
                logRef = s.trim();
            }
        }
	        emitter.publishAlert(Alerts.ioOutOfData(
	            identity.instanceId(),
	            io.pockethive.control.ControlScope.forInstance(identity.swarmId(), identity.role(), identity.instanceId()),
	            null,
	            null,
	            runtimeMeta,
	            dataset,
	            null,
	            logRef,
	            context,
            Instant.now()
        ));
    }

	    private Map<String, Object> buildRuntimeMeta() {
	        Map<String, Object> meta = new LinkedHashMap<>();
	        meta.put("templateId", requireEnvValue("POCKETHIVE_TEMPLATE_ID"));
	        meta.put("runId", requireEnvValue("POCKETHIVE_JOURNAL_RUN_ID"));
	        meta.put("containerId", envValue("HOSTNAME"));
	        meta.put("image", envValue("POCKETHIVE_RUNTIME_IMAGE"));
	        meta.put("stackName", envValue("POCKETHIVE_RUNTIME_STACK_NAME"));
	        return Collections.unmodifiableMap(meta);
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

    private static String asIoState(Object value, List<String> allowed) {
        if (!(value instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return allowed.contains(trimmed) ? trimmed : null;
    }

    private static boolean isBetter(String candidate, String current, List<String> precedence) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        int candidateIdx = precedence.indexOf(candidate);
        int currentIdx = precedence.indexOf(current);
        if (candidateIdx < 0) {
            return false;
        }
        if (currentIdx < 0) {
            return true;
        }
        return candidateIdx < currentIdx;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contextMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        return (Map<String, Object>) raw;
    }

    private record IoStateAggregate(String workInput, String workOutput, Map<String, Object> workContext) {
    }

    private Map<String, Object> statusConfigSnapshot(List<WorkerState> states) {
        if (states == null || states.isEmpty()) {
            return Map.of();
        }
        if (states.size() == 1) {
            Map<String, Object> cfg = snapshotRawConfig(states.getFirst());
            return cfg != null ? cfg : Map.of();
        }
        Map<String, Object> perWorker = new LinkedHashMap<>();
        for (WorkerState state : states) {
            if (state == null || state.definition() == null) {
                continue;
            }
            String beanName = state.definition().beanName();
            if (beanName == null || beanName.isBlank()) {
                continue;
            }
            Map<String, Object> cfg = snapshotRawConfig(state);
            if (cfg != null && !cfg.isEmpty()) {
                perWorker.put(beanName, cfg);
            }
        }
        if (perWorker.isEmpty()) {
            return Map.of();
        }
        return Map.of("workers", Map.copyOf(perWorker));
    }

    private StatusSnapshot collectSnapshot(Collection<WorkerState> states, boolean snapshotMode) {
        long processedTotal = 0L;
        boolean allEnabled = false;
        boolean seenWorker = false;
        List<Map<String, Object>> workers = new ArrayList<>();
        Set<String> workIn = new LinkedHashSet<>();
        Set<String> workOut = new LinkedHashSet<>();
        for (WorkerState state : states) {
            ensureStatusPublisher(state);
            WorkerDefinition def = state.definition();
            workIn.addAll(state.inboundRoutes());
            workOut.addAll(state.outboundRoutes());
            long processed = snapshotMode ? state.peekProcessedCount() : state.drainProcessedCount();
            processedTotal += processed;
            boolean enabled = state.enabled();
            boolean workerEnabled = enabled;
            if (!seenWorker) {
                allEnabled = workerEnabled;
                seenWorker = true;
            } else if (allEnabled) {
                allEnabled = workerEnabled;
            }
            Map<String, Object> workerEntry = new LinkedHashMap<>();
            workerEntry.put("worker", def.beanName());
            workerEntry.put("role", def.role());
            workerEntry.put("input", def.input().name());
            workerEntry.put("output", def.outputType().name());
            workerEntry.put(snapshotMode ? "processedTotal" : "processedDelta", processed);
            workerEntry.put("enabled", workerEnabled);
            String description = def.description();
            if (description != null) {
                workerEntry.put("description", description);
            }
            WorkIoBindings io = def.io();
            if (io.inboundQueue() != null) {
                workerEntry.put("inQueue", io.inboundQueue());
            }
            if (io.outboundQueue() != null) {
                workerEntry.put("outQueue", io.outboundQueue());
            }
            if (io.outboundExchange() != null) {
                workerEntry.put("exchange", io.outboundExchange());
            }
            if (!def.capabilities().isEmpty()) {
                List<String> capabilities = def.capabilities().stream()
                    .map(WorkerCapability::name)
                    .sorted()
                    .toList();
                workerEntry.put("capabilities", capabilities);
            }
            Map<String, Object> config = snapshotRawConfig(state);
            if (!config.isEmpty()) {
                // Ensure IO types are always present in status config so UIs do not need
                // to guess them from other fields. Types come from the resolved worker
                // definition, not heuristics.
                Map<String, Object> augmented = new LinkedHashMap<>(config);

                Map<String, Object> inputsBlock = null;
                Object existingInputs = augmented.get("inputs");
                if (existingInputs instanceof Map<?, ?> rawInputs) {
                    inputsBlock = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : rawInputs.entrySet()) {
                        Object key = entry.getKey();
                        if (key != null) {
                            inputsBlock.put(key.toString(), entry.getValue());
                        }
                    }
                }
                if (inputsBlock == null) {
                    inputsBlock = new LinkedHashMap<>();
                }
                if (!inputsBlock.containsKey("type")) {
                    inputsBlock.put("type", def.input().name());
                }
                if (!inputsBlock.isEmpty()) {
                    augmented.put("inputs", inputsBlock);
                }

                Map<String, Object> outputsBlock = null;
                Object existingOutputs = augmented.get("outputs");
                if (existingOutputs instanceof Map<?, ?> rawOutputs) {
                    outputsBlock = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : rawOutputs.entrySet()) {
                        Object key = entry.getKey();
                        if (key != null) {
                            outputsBlock.put(key.toString(), entry.getValue());
                        }
                    }
                }
                if (outputsBlock == null) {
                    outputsBlock = new LinkedHashMap<>();
                }
                if (!outputsBlock.containsKey("type")) {
                    outputsBlock.put("type", def.outputType().name());
                }
                if (!outputsBlock.isEmpty()) {
                    augmented.put("outputs", outputsBlock);
                }

                workerEntry.put("config", augmented);
            }
            Map<String, Object> statusData = state.statusData();
            if (!statusData.isEmpty()) {
                workerEntry.put("data", statusData);
            }
            workers.add(workerEntry);
        }
        return new StatusSnapshot(processedTotal, allEnabled, workers, workIn, workOut);
    }

    private WorkerStatusPublisher ensureStatusPublisher(WorkerState state) {
        StatusPublisher publisher = state.statusPublisher();
        if (publisher instanceof WorkerStatusPublisher workerStatusPublisher) {
            return workerStatusPublisher;
        }
        WorkerStatusPublisher created = new WorkerStatusPublisher(state, this::emitStatusSnapshot, this::emitStatusDelta);
        state.setStatusPublisher(created);
        return created;
    }

    private void notifyStateListeners(WorkerState state) {
        if (state == null) {
            return;
        }
        WorkerStateSnapshot snapshot = new WorkerStateSnapshot(state, snapshotRawConfig(state));
        List<Consumer<WorkerStateSnapshot>> listeners = stateListeners.getOrDefault(state.definition().beanName(), Collections.emptyList());
        listeners.forEach(listener -> safeInvoke(listener, snapshot));
        globalStateListeners.forEach(listener -> safeInvoke(listener, snapshot));
    }

    private void safeInvoke(Consumer<WorkerStateSnapshot> listener, WorkerStateSnapshot snapshot) {
        try {
            listener.accept(snapshot);
        } catch (RuntimeException ex) {
            log.warn("Worker state listener threw exception", ex);
        }
    }

    private static String[] resolveControlRoutes(ControlPlaneRouteCatalog routes, ControlPlaneIdentity identity) {
        if (routes == null) {
            return new String[0];
        }
        List<String> resolved = new ArrayList<>();
        resolved.addAll(expandRoutes(routes.configSignals(), identity));
        resolved.addAll(expandRoutes(routes.statusSignals(), identity));
        resolved.addAll(expandRoutes(routes.lifecycleSignals(), identity));
        resolved.addAll(expandRoutes(routes.statusEvents(), identity));
        resolved.addAll(expandRoutes(routes.lifecycleEvents(), identity));
        resolved.addAll(expandRoutes(routes.otherEvents(), identity));
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String route : resolved) {
            if (route != null && !route.isBlank()) {
                unique.add(route);
            }
        }
        return unique.toArray(String[]::new);
    }

    private static List<String> expandRoutes(Set<String> templates, ControlPlaneIdentity identity) {
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }
        return templates.stream()
            .filter(Objects::nonNull)
            .map(route -> route.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, identity.instanceId()))
            .toList();
    }

    private record StatusSnapshot(long processedTotal,
                                  boolean enabled,
                                  List<Map<String, Object>> workers,
                                  Set<String> workIn,
                                  Set<String> workOut) {
    }

    /**
     * Immutable view of a worker's current state for external listeners.
     */
    public static final class WorkerStateSnapshot {

        private final WorkerState state;
        private final Map<String, Object> rawConfig;

        private WorkerStateSnapshot(WorkerState state, Map<String, Object> rawConfig) {
            this.state = Objects.requireNonNull(state, "state");
            this.rawConfig = rawConfig == null || rawConfig.isEmpty() ? Map.of() : Map.copyOf(rawConfig);
        }

        /**
         * Returns the worker definition derived from {@link PocketHiveWorker} metadata.
         */
        public WorkerDefinition definition() {
            return state.definition();
        }

        /**
         * Indicates whether the worker is currently enabled according to the latest control-plane command.
         */
        public boolean enabled() {
            return state.enabled();
        }

        /**
         * Returns the raw configuration map received from the control plane.
         */
        public Map<String, Object> rawConfig() {
            return rawConfig;
        }

        /**
         * Returns the typed configuration if it can be converted to the requested class.
         */
        public <C> Optional<C> config(Class<C> type) {
            return state.config(type);
        }

        /**
         * Returns the structured status payload last published by the worker.
         */
        public Map<String, Object> statusData() {
            return state.statusData();
        }

        /**
         * Returns the optional worker description declared on {@link PocketHiveWorker}.
         */
        public Optional<String> description() {
            return Optional.ofNullable(state.definition().description());
        }

        /**
         * Returns the declared worker capabilities.
         */
        public Set<WorkerCapability> capabilities() {
            return state.definition().capabilities();
        }

        /**
         * Returns the configured worker input type.
         */
        public WorkerInputType inputType() {
            return state.definition().input();
        }

        /**
         * Returns the configured worker output type.
         */
        public WorkerOutputType outputType() {
            return state.definition().outputType();
        }

        /**
         * Returns the inbound queue if declared on the worker definition.
         */
        public Optional<String> inboundQueue() {
            return Optional.ofNullable(state.definition().io().inboundQueue());
        }

        /**
         * Returns the outbound queue if declared on the worker definition.
         */
        public Optional<String> outboundQueue() {
            return Optional.ofNullable(state.definition().io().outboundQueue());
        }

        /**
         * Returns the outbound exchange if declared on the worker definition.
         */
        public Optional<String> exchange() {
            return Optional.ofNullable(state.definition().io().outboundExchange());
        }

        /**
         * Returns the set of inbound work routes observed for the worker.
         */
        public Set<String> inboundRoutes() {
            return state.inboundRoutes();
        }

        /**
         * Returns the set of outbound work routes observed for the worker.
         */
        public Set<String> outboundRoutes() {
            return state.outboundRoutes();
        }
    }
}
