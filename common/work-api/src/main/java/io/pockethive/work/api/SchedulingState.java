package io.pockethive.work.api;

import java.util.Objects;

/**
 * Responsibility: carry a read-only scheduling projection from the state owner.
 * Must not: mutate worker configuration, revalidate accepted rate settings or decide dispatch policy.
 * Contract: RESP-WORK-SCHEDULE-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-schedule-contract.
 */
public record SchedulingState<C>(boolean enabled, long revision, SchedulingConfigState configState,
                                 C configuration, double ratePerSecond) {
    public SchedulingState {
        Objects.requireNonNull(configState, "configState");
        if ((configState == SchedulingConfigState.CONFIGURED) != (configuration != null)) {
            throw new IllegalArgumentException("Configuration availability does not match its value");
        }
    }
}
