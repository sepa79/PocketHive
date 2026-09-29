package io.pockethive.work.local.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SchedulerRunStateTest {
    @Test
    void clipsFiniteQuotaAndProjectsRemainingIncludingLongLimits() {
        var run = run(2);
        assertThat(run.limitQuota(5, run.maxMessages())).isEqualTo(2);
        assertThat(run.recordDispatch()).isEqualTo(1);
        assertThat(run.limitQuota(5, run.maxMessages())).isEqualTo(1);
        assertThat(run.recordDispatch()).isZero();
        assertThat(run.limitQuota(5, run.maxMessages())).isZero();
        assertThat(run.diagnostics(2)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "ratePerSec", 2.5, "maxMessages", 2L, "dispatched", 2L,
            "remaining", 0L, "exhausted", true));

        run.applyControls(config(Map.of("maxMessages", Long.MAX_VALUE)));
        assertThat(run.limitQuota(Integer.MAX_VALUE, run.maxMessages())).isEqualTo(Integer.MAX_VALUE);
        assertThat(run.recordDispatch()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void unchangedLimitAndRateUpdateKeepCountButChangedLimitAndExplicitResetClearIt() {
        var run = run(3);
        run.recordDispatch();
        run.applyControls(config(Map.of("maxMessages", 3, "ratePerSec", 0.5, "reset", false)));
        assertThat(run.ratePerSec()).isEqualTo(0.5);
        assertThat(run.dispatchedCount()).isEqualTo(1);
        assertThat(run.recordDispatch()).isEqualTo(1);
        run.applyControls(config(Map.of("reset", true)));
        assertThat(run.recordDispatch()).isEqualTo(2);
        run.applyControls(config(Map.of("maxMessages", 0)));
        assertThat(run.dispatchedCount()).isZero();
        assertThat(run.limitQuota(5, run.maxMessages())).isEqualTo(5);
        assertThat(run.recordDispatch()).isEqualTo(-1);
        assertThat(run.diagnostics(0)).containsEntry("dispatched", 1L)
            .containsEntry("exhausted", false).doesNotContainKey("remaining");
    }

    @Test
    void rejectedControlsPreserveAllRuntimeValuesAndCount() {
        var run = run(3);
        run.recordDispatch();
        var before = run.diagnostics(3);
        var patch = new LinkedHashMap<String, Object>();
        patch.put("ratePerSec", 99);
        patch.put("maxMessages", null);
        assertThatThrownBy(() -> run.applyControls(config(patch)))
            .hasMessageContaining("inputs.scheduler.maxMessages");
        assertThat(run.diagnostics(run.maxMessages())).isEqualTo(before);
        patch.put("maxMessages", 100);
        patch.put("reset", "true");
        assertThatThrownBy(() -> run.applyControls(config(patch)))
            .hasMessageContaining("inputs.scheduler.reset");
        assertThat(run.diagnostics(run.maxMessages())).isEqualTo(before);
        patch.put("ratePerSec", -1);
        patch.put("reset", true);
        assertThatThrownBy(() -> run.applyControls(config(patch)))
            .hasMessageContaining("inputs.scheduler.ratePerSec");
        assertThat(run.diagnostics(run.maxMessages())).isEqualTo(before);
    }

    @Test
    void dispatchUsesCurrentLimitWhileDiagnosticsKeepTheTicksCapturedLimit() {
        var run = run(2);
        long tickLimit = run.maxMessages();
        assertThat(run.limitQuota(5, tickLimit)).isEqualTo(2);
        run.recordDispatch();
        run.applyControls(config(Map.of("maxMessages", 4)));
        assertThat(run.recordDispatch()).isEqualTo(3);
        assertThat(run.diagnostics(tickLimit)).containsEntry("maxMessages", 2L)
            .containsEntry("dispatched", 1L).containsEntry("remaining", 1L);
        assertThat(run.diagnostics(run.maxMessages())).containsEntry("remaining", 3L);
    }

    private SchedulerRunState run(long maxMessages) {
        var settings = new SchedulerSettingsParser().parse(Map.of(
            "ratePerSec", 2.5, "maxMessages", maxMessages), SchedulerSettingsParser.PATH);
        return new SchedulerRunState(settings, "test-scheduler", LoggerFactory.getLogger(getClass()));
    }

    private Map<String, Object> config(Map<String, Object> controls) {
        return Map.of("inputs", Map.of("scheduler", controls));
    }
}
