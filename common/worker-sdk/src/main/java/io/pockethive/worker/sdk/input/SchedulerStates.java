package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
     * Marker interface for configurations that expose rate-per-second scheduling semantics.
     */
    public interface RateConfig {

        boolean enabled();

        double ratePerSec();

        boolean singleRequest();
    }

    /**
     * Creates a scheduler state that honours {@code enabled}, {@code ratePerSec}, and
     * {@code singleRequest} fields from the supplied configuration type. The returned state mirrors
     * the legacy generator loop: it accumulates fractional rates, dispatches single-shot requests
     * once, and disables invocations when the control plane toggles the worker off.
     *
     * @param configType configuration class published to the control plane
     * @param defaults   supplier that returns the service defaults for the worker
     */
    public static <C extends RateConfig> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Supplier<C> defaults
    ) {
        return ratePerSecond(configType, defaults, LoggerFactory.getLogger(RatePerSecondState.class));
    }

    /**
     * Variant of {@link #ratePerSecond(Class, Supplier)} that uses the provided logger instance.
     */
    public static <C extends RateConfig> SchedulerState<C> ratePerSecond(
        Class<C> configType,
        Supplier<C> defaults,
        Logger log
    ) {
        return new RatePerSecondState<>(configType, defaults, log);
    }

    private static final class RatePerSecondState<C extends RateConfig> implements SchedulerState<C> {

        private final Class<C> configType;
        private final Supplier<C> defaults;
        private final Logger log;
        private final AtomicBoolean singleRequestPending = new AtomicBoolean(false);

        private volatile C config;
        private volatile boolean enabled;
        private double carryOver;

        private RatePerSecondState(Class<C> configType, Supplier<C> defaults, Logger log) {
            this.configType = Objects.requireNonNull(configType, "configType");
            Objects.requireNonNull(defaults, "defaults");
            this.defaults = () -> Objects.requireNonNull(defaults.get(), "defaults supplier returned null");
            this.log = log != null ? log : LoggerFactory.getLogger(RatePerSecondState.class);
            C initial = this.defaults.get();
            this.config = initial;
            this.enabled = initial.enabled();
            this.carryOver = 0.0;
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler initialised: enabled={}, ratePerSec={}, singleRequest={}",
                    configType.getSimpleName(), enabled, initial.ratePerSec(), initial.singleRequest());
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
            boolean resolvedEnabled = snapshot.enabled()
                .orElseGet(() -> previous == null ? incoming.enabled() : enabled);
            this.config = incoming;
            boolean configChanged = !Objects.equals(previous, incoming);
            boolean enabledChanged = resolvedEnabled != this.enabled;
            this.enabled = resolvedEnabled;
            if (configChanged && incoming.singleRequest()) {
                singleRequestPending.set(true);
            }
            if (!resolvedEnabled) {
                carryOver = 0.0;
                singleRequestPending.set(false);
            }
            if (log.isDebugEnabled() && (configChanged || enabledChanged)) {
                log.debug("{} scheduler updated: enabled={}, ratePerSec={}, singleRequest={}, reason={}",
                    configType.getSimpleName(),
                    resolvedEnabled,
                    incoming.ratePerSec(),
                    incoming.singleRequest(),
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
                singleRequestPending.set(false);
                if (log.isDebugEnabled()) {
                    log.debug("{} scheduler disabled at tick {}; skipping dispatch", configType.getSimpleName(), nowMillis);
                }
                return 0;
            }
            double rate = Math.max(0.0, config.ratePerSec());
            double planned = rate + carryOver;
            int quota = (int) Math.floor(planned);
            carryOver = planned - quota;
            if (singleRequestPending.getAndSet(false)) {
                quota += 1;
                if (log.isDebugEnabled()) {
                    log.debug("{} scheduler consumed single-request override at tick {}", configType.getSimpleName(), nowMillis);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("{} scheduler tick {} resolved quota={} (rate={}, carryOver={})",
                    configType.getSimpleName(), nowMillis, quota, rate, carryOver);
            }
            return quota;
        }
    }
}
