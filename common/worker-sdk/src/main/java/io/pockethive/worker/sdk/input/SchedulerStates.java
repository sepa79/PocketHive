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
     */
    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType
    ) {
        return ratePerSecond(configType, LoggerFactory.getLogger(RatePerSecondState.class), () -> false, null);
    }

    /**
     * Variant of {@link #ratePerSecond(Class)} that uses the provided logger instance.
     */
    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Logger log
    ) {
        return ratePerSecond(configType, log, () -> false, null);
    }

    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Logger log,
        Supplier<Boolean> initialEnabled
    ) {
        return ratePerSecond(configType, log, initialEnabled, null);
    }

    public static <C> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Logger log,
        Supplier<Boolean> initialEnabled,
        DoubleSupplier rateSupplier
    ) {
        return new RatePerSecondState<>(configType, log, initialEnabled, rateSupplier);
    }

    private static final class RatePerSecondState<C> implements SchedulerState<C> {

        private final Class<C> configType;
        private final Logger log;
        private final Supplier<Boolean> initialEnabledSupplier;
        private final DoubleSupplier rateSupplier;

        private volatile boolean enabled;
        private double carryOver;

        private RatePerSecondState(Class<C> configType,
                                   Logger log,
                                   Supplier<Boolean> initialEnabledSupplier,
                                   DoubleSupplier rateSupplier) {
            this.configType = Objects.requireNonNull(configType, "configType");
            this.log = log != null ? log : LoggerFactory.getLogger(RatePerSecondState.class);
            this.initialEnabledSupplier = initialEnabledSupplier == null ? () -> false : initialEnabledSupplier;
            this.rateSupplier = rateSupplier;
            this.enabled = Boolean.TRUE.equals(this.initialEnabledSupplier.get());
            this.carryOver = 0.0;
            if (this.log.isDebugEnabled()) {
                double initialRate = rateSupplier != null ? Math.max(0.0, rateSupplier.getAsDouble()) : 0.0;
                this.log.debug("{} scheduler initialised: enabled={}, ratePerSec={}",
                    configType.getSimpleName(), enabled, initialRate);
            }
        }

        @Override
        public synchronized void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            boolean resolvedEnabled = snapshot.enabled();
            boolean hasConfig = snapshot.config(configType).isPresent();
            boolean enabledChanged = resolvedEnabled != this.enabled;
            this.enabled = resolvedEnabled;
            if (!resolvedEnabled) {
                carryOver = 0.0;
            }
            if (log.isDebugEnabled() && (hasConfig || enabledChanged)) {
                double updatedRate = rateSupplier != null ? Math.max(0.0, rateSupplier.getAsDouble()) : 0.0;
                log.debug("{} scheduler updated: enabled={}, ratePerSec={}, reason={}",
                    configType.getSimpleName(),
                    resolvedEnabled,
                    updatedRate,
                    enabledChanged ? "enabled toggled" : "config received");
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
