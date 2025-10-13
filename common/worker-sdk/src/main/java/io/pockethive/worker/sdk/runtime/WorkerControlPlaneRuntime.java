package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandState;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
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
    private final ControlPlaneTopologyDescriptor topology;
    private final String controlQueueName;
    private final String[] controlRoutes;
    private final WorkerSignalListener signalListener = new WorkerSignalDispatcher();
    private final Map<String, List<Consumer<WorkerStateSnapshot>>> stateListeners = new ConcurrentHashMap<>();
    private final List<Consumer<WorkerStateSnapshot>> globalStateListeners = new CopyOnWriteArrayList<>();

    public WorkerControlPlaneRuntime(
        WorkerControlPlane workerControlPlane,
        WorkerStateStore stateStore,
        ObjectMapper objectMapper,
        ControlPlaneEmitter emitter,
        ControlPlaneIdentity identity,
        ControlPlaneTopologyDescriptor topology
    ) {
        this.workerControlPlane = Objects.requireNonNull(workerControlPlane, "workerControlPlane");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.controlQueueName = topology.controlQueue(identity.instanceId()).map(ControlQueueDescriptor::name).orElse(null);
        this.controlRoutes = resolveControlRoutes(topology.routes(), identity);
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
            .map(state -> state.rawConfig().isEmpty() ? Map.<String, Object>of() : state.rawConfig())
            .orElse(Map.of());
    }

    /**
     * Returns the last known enablement flag for the worker bean, if the control-plane has provided one.
     */
    public Optional<Boolean> workerEnabled(String workerBeanName) {
        Objects.requireNonNull(workerBeanName, "workerBeanName");
        return stateStore.find(workerBeanName).flatMap(WorkerState::enabled);
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
            safeInvoke(listener, new WorkerStateSnapshot(state));
        }
    }

    /**
     * Registers a listener that will be notified for every worker state change. Existing worker snapshots are
     * delivered immediately upon registration.
     */
    public void registerGlobalStateListener(Consumer<WorkerStateSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        globalStateListeners.add(listener);
        stateStore.all().forEach(state -> safeInvoke(listener, new WorkerStateSnapshot(state)));
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
            log.debug("Received status-request signal {} => emitting snapshot", request.signal());
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
            Map<String, Object> workerConfig = workerConfigFor(state, sanitized);
            Boolean enabled = resolveEnabled(workerConfig, command.enabled());
            Map<String, Object> readyConfig = workerConfig;
            try {
                Map<String, Object> storedConfig;
                Object typedConfig;
                if (shouldReuseExistingConfig(state, workerConfig)) {
                    storedConfig = mergeWithExistingConfig(state, enabled);
                    typedConfig = existingTypedConfig(state);
                    if (typedConfig == null) {
                        typedConfig = convertConfig(state.definition(), storedConfig);
                    }
                } else {
                    storedConfig = workerConfig;
                    typedConfig = convertConfig(state.definition(), workerConfig);
                }
                state.updateConfig(typedConfig, storedConfig, enabled);
                emitConfigReady(signal, state, readyConfig, enabled);
                notifyStateListeners(state);
            } catch (Exception ex) {
                emitConfigError(signal, state, ex);
                log.warn("Failed to apply config update for worker {}", state.definition().beanName(), ex);
            }
        }
        emitStatusSnapshot();
    }

    private Object convertConfig(WorkerDefinition definition, Map<String, Object> rawConfig) {
        Class<?> configType = definition.configType();
        if (configType == Void.class || rawConfig.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(rawConfig, configType);
        } catch (IllegalArgumentException ex) {
            String message = "Unable to convert control-plane config for worker '%s' to type %s".formatted(
                definition.beanName(), configType.getSimpleName());
            throw new IllegalArgumentException(message, ex);
        }
    }

    private void emitConfigReady(ControlSignal signal, WorkerState state, Map<String, Object> rawConfig, Boolean enabled) {
        if (!hasCorrelation(signal)) {
            log.debug("Skipping ready confirmation for signal {} due to missing correlation/idempotency", signal.signal());
            return;
        }
        Map<String, Object> commandDetails = new LinkedHashMap<>();
        if (!rawConfig.isEmpty()) {
            commandDetails.put("config", rawConfig);
        }
        CommandState commandState = new CommandState("applied", enabled, commandDetails.isEmpty() ? null : commandDetails);
        Map<String, Object> confirmationDetails = new LinkedHashMap<>();
        confirmationDetails.put("worker", state.definition().beanName());
        Map<String, Object> statusData = state.statusData();
        if (!statusData.isEmpty()) {
            confirmationDetails.put("data", statusData);
        }
        if (!rawConfig.isEmpty()) {
            confirmationDetails.put("config", rawConfig);
        }
        ControlPlaneEmitter.ReadyContext.Builder ready = ControlPlaneEmitter.ReadyContext.builder(
            signal.signal(), signal.correlationId(), signal.idempotencyKey(), commandState
        );
        if (!confirmationDetails.isEmpty()) {
            ready.details(confirmationDetails);
        }
        emitter.emitReady(ready.build());
    }

    private void emitConfigError(ControlSignal signal, WorkerState state, Exception error) {
        if (!hasCorrelation(signal)) {
            log.debug("Skipping error confirmation for signal {} due to missing correlation/idempotency", signal.signal());
            return;
        }
        String code = error.getClass().getSimpleName();
        String message = error.getMessage() == null || error.getMessage().isBlank() ? code : error.getMessage();
        CommandState commandState = new CommandState("failed", state.enabled().orElse(null), null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("worker", state.definition().beanName());
        details.put("exception", code);
        Map<String, Object> statusData = state.statusData();
        if (!statusData.isEmpty()) {
            details.put("data", statusData);
        }
        ControlPlaneEmitter.ErrorContext.Builder builder = ControlPlaneEmitter.ErrorContext.builder(
            signal.signal(), signal.correlationId(), signal.idempotencyKey(), commandState, CONFIG_PHASE, code, message
        ).retryable(Boolean.FALSE);
        builder.details(details);
        emitter.emitError(builder.build());
        notifyStateListeners(state);
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
        return Map.copyOf(sanitized);
    }

    private Map<String, Object> workerConfigFor(WorkerState state, Map<String, Object> sanitized) {
        if (sanitized.isEmpty()) {
            return Map.of();
        }
        String beanName = state.definition().beanName();
        Object workersSection = sanitized.get("workers");
        if (workersSection instanceof Map<?, ?> workersMap) {
            Object candidate = workersMap.get(beanName);
            if (candidate instanceof Map<?, ?> nested) {
                return copyMap(nested);
            }
        }
        Object direct = sanitized.get(beanName);
        if (direct instanceof Map<?, ?> nested) {
            return copyMap(nested);
        }
        return sanitized;
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(key.toString(), value);
            }
        });
        return Map.copyOf(copy);
    }

    private boolean shouldReuseExistingConfig(WorkerState state, Map<String, Object> workerConfig) {
        if (workerConfig == null || workerConfig.isEmpty()) {
            return true;
        }
        for (String key : workerConfig.keySet()) {
            if (!"enabled".equals(key)) {
                return false;
            }
        }
        return state.definition().configType() != Void.class;
    }

    private Map<String, Object> mergeWithExistingConfig(WorkerState state, Boolean enabled) {
        Map<String, Object> existing = state.rawConfig();
        if (existing.isEmpty() && enabled == null) {
            return Map.of();
        }
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        if (enabled != null) {
            merged.put("enabled", enabled);
        }
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    private Object existingTypedConfig(WorkerState state) {
        Class<?> configType = state.definition().configType();
        if (configType == Void.class) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Class<Object> castType = (Class<Object>) configType;
        return state.config(castType).orElse(null);
    }

    private Boolean resolveEnabled(Map<String, Object> workerConfig, Boolean commandEnabled) {
        if (commandEnabled != null) {
            return commandEnabled;
        }
        Object candidate = workerConfig.get("enabled");
        if (candidate instanceof Boolean b) {
            return b;
        }
        if (candidate instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
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
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private void emitStatus(boolean snapshot) {
        List<WorkerState> states = new ArrayList<>(stateStore.all());
        if (states.isEmpty()) {
            log.debug("Skipping status emission; no worker states registered");
            return;
        }
        StatusSnapshot snapshotData = collectSnapshot(states, snapshot);
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
            builder.tps(snapshotData.processedTotal());
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
            Boolean enabled = state.enabled().orElse(null);
            boolean workerEnabled = Boolean.TRUE.equals(enabled);
            if (!seenWorker) {
                allEnabled = workerEnabled;
                seenWorker = true;
            } else if (allEnabled) {
                allEnabled = workerEnabled;
            }
            Map<String, Object> workerEntry = new LinkedHashMap<>();
            workerEntry.put("worker", def.beanName());
            workerEntry.put("role", def.role());
            workerEntry.put(snapshotMode ? "processedTotal" : "processedDelta", processed);
            workerEntry.put("enabled", workerEnabled);
            Map<String, Object> config = state.rawConfig();
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
        WorkerStateSnapshot snapshot = new WorkerStateSnapshot(state);
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

        private WorkerStateSnapshot(WorkerState state) {
            this.state = Objects.requireNonNull(state, "state");
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
        public Optional<Boolean> enabled() {
            return state.enabled();
        }

        /**
         * Returns the raw configuration map received from the control plane.
         */
        public Map<String, Object> rawConfig() {
            return state.rawConfig();
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
