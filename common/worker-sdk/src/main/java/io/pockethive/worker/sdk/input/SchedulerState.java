package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;

/**
 * Maintains the scheduling state for a worker fed by {@link SchedulerWorkInput}. Implementations are
 * responsible for interpreting control-plane snapshots, tracking enablement flags, and calculating the
 * number of invocations that should be triggered for each scheduler tick.
 *
 * @param <C> type of the worker configuration managed by the control plane
 */
public interface SchedulerState<C> {

    /**
     * Returns the default configuration that should be registered with the control plane before any
     * overrides are applied.
     */
    C defaultConfig();

    /**
     * Applies the latest control-plane snapshot. Implementations may merge the snapshot with defaults and
     * update any internal counters required for subsequent ticks.
     *
     * @param snapshot control-plane view of the worker state
     */
    void update(WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot);

    /**
     * Indicates whether the scheduler should currently dispatch work.
     */
    boolean isEnabled();

    /**
     * Calculates how many worker invocations should be triggered for the given timestamp. Implementations
     * are expected to update their internal state (for example rate limit carry-over or last invocation
     * timestamps) as part of this computation.
     *
     * @param nowMillis current wall-clock time in milliseconds
     * @return number of invocations to trigger for this tick
     */
    int planInvocations(long nowMillis);
}
