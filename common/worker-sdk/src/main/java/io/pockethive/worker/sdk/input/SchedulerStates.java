package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory methods for reusable {@link SchedulerState} implementations backed by control-plane
 * configuration. Services can rely on these helpers instead of creating bespoke scheduler state
 * classes per worker.
 */
public final class SchedulerStates {

    private SchedulerStates() {
    }

    /**
     * Creates a scheduler state that honours {@code enabled} and {@code ratePerSec} fields from the
     * supplied configuration type. The returned state accumulates fractional rates and disables
     * invocations when the control plane toggles the worker off.
     *
     * @param configType configuration class published to the control plane
     * @param defaults   supplier that returns the service defaults for the worker
     */
    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Supplier<C> defaults
    ) {
        return ratePerSecond(configType, defaults, LoggerFactory.getLogger(RatePerSecondState.class), () -> true, null);
    }

    /**
     * Variant of {@link #ratePerSecond(Class, Supplier)} that uses the provided logger instance.
     */
    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Supplier<C> defaults,
        Logger log
    ) {
        return ratePerSecond(configType, defaults, log, () -> true, null);
    }

    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Supplier<C> defaults,
        Logger log,
        Supplier<Boolean> defaultEnabled
    ) {
        return ratePerSecond(configType, defaults, log, defaultEnabled, null);
    }

    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Supplier<C> defaults,
        Logger log,
        Supplier<Boolean> defaultEnabled,
        DoubleSupplier rateSupplier
    ) {
        return new RatePerSecondState<>(configType, defaults, log, defaultEnabled, rateSupplier);
    }

    private static final class RatePerSecondState<C> implements SchedulerState<C> {

        private final Class<C> configType;
        private final Supplier<C> defaults;
        private final Logger log;
        private final Supplier<Boolean> defaultEnabledSupplier;
        private final DoubleSupplier rateSupplier;

        private volatile C config;
        private volatile boolean enabled;
        private double carryOver;

        private RatePerSecondState(Class<C> configType,
                                   Supplier<C> defaults,
                                   Logger log,
                                   Supplier<Boolean> defaultEnabledSupplier,
                                   DoubleSupplier rateSupplier) {
            this.configType = Objects.requireNonNull(configType, "configType");
            Objects.requireNonNull(defaults, "defaults");
            this.defaults = () -> Objects.requireNonNull(defaults.get(), "defaults supplier returned null");
            this.log = log != null ? log : LoggerFactory.getLogger(RatePerSecondState.class);
            this.defaultEnabledSupplier = defaultEnabledSupplier == null ? () -> true : defaultEnabledSupplier;
            this.rateSupplier = rateSupplier;
            C initial = this.defaults.get();
            this.config = initial;
            this.enabled = Boolean.TRUE.equals(this.defaultEnabledSupplier.get());
            this.carryOver = 0.0;
            if (log.isDebugEnabled()) {
                double initialRate = rateSupplier != null ? Math.max(0.0, rateSupplier.getAsDouble()) : 0.0;
                log.debug("{} scheduler initialised: enabled={}, ratePerSec={}",
                    configType.getSimpleName(), enabled, initialRate);
            }
        }

        @Override
        public synchronized C defaultConfig() {
            return defaults.get();
        }

        @Override
        public synchronized void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            C incoming = snapshot.config(configType).orElseGet(defaults);
            C previous = this.config;
            boolean resolvedEnabled = snapshot.enabled();
            this.config = incoming;
            boolean configChanged = !Objects.equals(previous, incoming);
            boolean enabledChanged = resolvedEnabled != this.enabled;
            this.enabled = resolvedEnabled;
            if (!resolvedEnabled) {
                carryOver = 0.0;
            }
            if (log.isDebugEnabled() && (configChanged || enabledChanged)) {
                double updatedRate = rateSupplier != null ? Math.max(0.0, rateSupplier.getAsDouble()) : 0.0;
                log.debug("{} scheduler updated: enabled={}, ratePerSec={}, reason={}",
                    configType.getSimpleName(),
                    resolvedEnabled,
                    updatedRate,
                    enabledChanged ? "enabled toggled" : "config changed");
            }
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public synchronized int planInvocations(long nowMillis) {
            if (!enabled) {
                carryOver = 0.0;
                if (log.isDebugEnabled()) {
                    log.debug("{} scheduler disabled at tick {}; skipping dispatch", configType.getSimpleName(), nowMillis);
                }
                return 0;
            }
            double rate = rateSupplier != null
                ? Math.max(0.0, rateSupplier.getAsDouble())
                : 0.0;
            double planned = rate + carryOver;
            int quota = (int) Math.floor(planned);
            carryOver = planned - quota;
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler tick {} resolved quota={} (rate={}, carryOver={})",
                    configType.getSimpleName(), nowMillis, quota, rate, carryOver);
            }
            return quota;
        }
    }
}
