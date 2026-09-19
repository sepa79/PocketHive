package io.pockethive.worker.sdk.input;

import io.pockethive.work.api.ScheduledInvocationPolicy;
import io.pockethive.work.api.SchedulingState;

/**
 * Responsibility: accumulate fractional quotas for the configured scheduler rate.
 * Must not: read Control Plane, mutate settings, or dispatch work.
 * Contract: RESP-WORK-RATE-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-rate-policy.
 */
public final class RateSchedulePolicy implements ScheduledInvocationPolicy<Object> {
    private double carryOver;
    private SchedulingState<Object> state;

    @Override
    public Class<Object> configurationType() { return Object.class; }

    @Override
    public synchronized void update(SchedulingState<Object> state) {
        this.state = java.util.Objects.requireNonNull(state, "state");
        if (!state.enabled()) {
            carryOver = 0;
        }
    }

    @Override
    public synchronized int plan(long tickMillis) {
        if (state == null) {
            throw new IllegalStateException("Scheduling state must be supplied before planning");
        }
        if (!state.enabled()) {
            carryOver = 0;
            return 0;
        }
        double planned = state.ratePerSecond() + carryOver;
        int quota = (int) Math.floor(planned);
        carryOver = planned - quota;
        return quota;
    }
}
