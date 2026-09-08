package io.pockethive.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.work.api.SchedulingConfigState;
import io.pockethive.work.api.SchedulingState;
import org.junit.jupiter.api.Test;

class TriggerSchedulePolicyTest {
    @Test
    void preservesIntervalAndSingleRequestAcrossEnablementAndRevisions() {
        var policy = new TriggerSchedulePolicy();
        var enabled = state(true, 1, true);
        policy.update(enabled);
        assertThat(policy.plan(-10_000)).isEqualTo(2); // Monotonic clocks may have a negative origin.
        assertThat(policy.plan(-9_999)).isZero();
        assertThat(policy.plan(-9_000)).isEqualTo(1);
        policy.update(state(false, 2, true));
        assertThat(policy.plan(-8_999)).isZero();
        var reenabled = state(true, 3, false);
        policy.update(reenabled);
        assertThat(policy.plan(-8_998)).isEqualTo(2); // Pending single request survives disablement.
        assertThat(policy.plan(-8_997)).isZero();
    }

    private SchedulingState<TriggerWorkerConfig> state(boolean enabled, long revision, boolean single) {
        var config = new TriggerWorkerConfig(1_000, single, TriggerWorkerConfig.ACTION_SHELL,
            "true", null, null, null, null);
        return new SchedulingState<>(enabled, revision, SchedulingConfigState.CONFIGURED, config, 0);
    }
}
