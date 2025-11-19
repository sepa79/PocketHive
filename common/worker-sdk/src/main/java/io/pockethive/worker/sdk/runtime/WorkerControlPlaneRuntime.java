package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private static final String CONFIG_PHASE = "apply";

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

    /**
     * Tracks the most recent status-delta emission so we can derive a per-second throughput
     * rather than reporting raw interval counts. Snapshots reuse the last computed values.
     */
    private volatile long lastDeltaStatusAtMillis = 0L;
    private volatile double lastComputedTps = 0.0;
    private volatile double lastIntervalSeconds = 0.0;

    public WorkerControlPlaneRuntime(
        WorkerControlPlane workerControlPlane,
        WorkerStateStore stateStore,
        ObjectMapper objectMapper,
        ControlPlaneEmitter emitter,
        ControlPlaneIdentity identity,
        WorkerControlPlaneProperties.ControlPlane controlPlane
    ) {
        this.workerControlPlane = Objects.requireNonNull(workerControlPlane, "workerControlPlane");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.configMerger = new ConfigMerger(this.objectMapper);
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
            log.debug("Ignoring unsupported control-plane signal: {}", context.envelope().signal().signal());
        }
    }

    private void handleConfigUpdate(WorkerConfigCommand command) {
        ControlSignal signal = command.signal();
        List<WorkerState> targets = resolveTargets(command);
        if (targets.isEmpty()) {
            log.warn("No worker definitions matched config-update signal role={} instance={}", signal.role(), signal.instance());
            return;
        }
        Map<String, Object> sanitized = sanitiseConfig(command.data());
        for (WorkerState state : targets) {
            ensureStatusPublisher(state);
            WorkerConfigPatch patch = workerConfigFor(state, sanitized);
            Map<String, Object> filteredUpdate = withoutNullValues(patch.values());
            boolean previousEnabled = state.enabled();
            try {
                ConfigMerger.ConfigMergeResult mergeResult = configMerger.merge(
                    state.definition(),
                    state.rawConfig(),
                    filteredUpdate,
                    patch.resetRequested()
                );
                Boolean enabled = command.enabled();
                if (log.isDebugEnabled()) {
                    log.debug("Applying config-update for worker={} role={} previousEnabled={} requestedEnabled={} data={}",
                        state.definition().beanName(),
                        state.definition().role(),
                        previousEnabled,
                        enabled,
                        filteredUpdate);
                }
                state.updateConfig(mergeResult.typedConfig(), mergeResult.replaced(), enabled);
                state.updateRawConfig(mergeResult.rawConfig());
                Map<String, Object> appliedConfig = mergeResult.replaced() ? mergeResult.rawConfig() : Map.of();
                if (hasCorrelation(signal)) {
                    notifier.emitConfigReady(signal, state, appliedConfig, enabled);
                } else {
                    log.debug(
                        "Skipping ready confirmation for signal {} due to missing correlation/idempotency",
                        signal.signal()
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
                    log.debug(
                        "Skipping error confirmation for signal {} due to missing correlation/idempotency",
                        signal.signal()
                    );
                }
                notifyStateListeners(state);
                log.warn("Failed to apply config update for worker {}", state.definition().beanName(), ex);
            }
        }
        emitStatusSnapshot();
    }

    private String resolveSignalName(WorkerStatusRequest request) {
        ControlSignal signal = request.signal();
        if (signal != null && signal.signal() != null && !signal.signal().isBlank()) {
            return signal.signal();
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
        String role = normaliseRole(signal.role());
        CommandTarget target = signal.commandTarget();
        List<WorkerState> matches = new ArrayList<>();
        for (WorkerState state : stateStore.all()) {
            WorkerDefinition definition = state.definition();
            if (!roleMatches(role, definition.role())) {
                continue;
            }
            if (!commandTargetIncludes(target)) {
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

    private Map<String, Object> withoutNullValues(Map<String, Object> candidate) {
        if (candidate.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        candidate.forEach((key, value) -> {
            if (value != null) {
                filtered.put(key, value);
            }
        });
        if (filtered.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(filtered);
    }

    private Map<String, Object> snapshotRawConfig(WorkerState state) {
        return state.rawConfig();
    }

    private Object currentTypedConfig(WorkerState state) {
        Class<?> configType = state.definition().configType();
        if (configType == Void.class) {
            return null;
        }
        return state.config(configType).orElse(null);
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
        if (signalRole == null || signalRole.equals("all")) {
            return true;
        }
        return signalRole.equalsIgnoreCase(workerRole);
    }

    private boolean commandTargetIncludes(CommandTarget target) {
        if (target == null) {
            return true;
        }
        return switch (target) {
            case ALL, ROLE, SWARM -> true;
            case INSTANCE -> true; // Routing already targeted to this instance by queue binding
        };
    }

    private String normaliseRole(String role) {
        if (role == null) {
            return null;
        }
        String trimmed = role.trim();
        if (trimmed.isEmpty()) {
            return null;
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
            builder.tps(Math.round(tps));
            builder.data("intervalSeconds", intervalSeconds);
            builder.data("workers", snapshotData.workers());
            builder.data("snapshot", snapshot);
        };
        ControlPlaneEmitter.StatusContext statusContext = ControlPlaneEmitter.StatusContext.of(customiser);
        if (snapshot) {
            emitter.emitStatusSnapshot(statusContext);
        } else {
            emitter.emitStatusDelta(statusContext);
        }
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
                workerEntry.put("config", config);
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
