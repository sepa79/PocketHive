package io.pockethive.work.local.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.work.api.SchedulingConfigState;
import io.pockethive.work.api.SchedulingState;
import org.junit.jupiter.api.Test;

class RateSchedulePolicyTest {
    @Test
    void carriesFractionalQuotaAndResetsOnDisablement() {
        var policy = new RateSchedulePolicy();
        var enabled = state(true, 0.4);
        policy.update(enabled);
        assertThat(policy.plan(0)).isZero();
        assertThat(policy.plan(1_000)).isZero();
        assertThat(policy.plan(2_000)).isEqualTo(1);
        policy.update(state(false, 0.4));
        policy.update(enabled); // Disable/enable between ticks must also clear fractional carry.
        assertThat(policy.plan(4_000)).isZero();
        assertThat(policy.plan(5_000)).isZero();
        assertThat(policy.plan(6_000)).isEqualTo(1);
    }
    private SchedulingState<Object> state(boolean enabled, double rate) {
        return new SchedulingState<>(enabled, 1, SchedulingConfigState.UNCONFIGURED, null, rate);
    }
}
