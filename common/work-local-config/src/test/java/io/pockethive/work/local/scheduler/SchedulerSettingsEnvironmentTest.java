package io.pockethive.work.local.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkConfigurationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchedulerSettingsEnvironmentTest {
    private final SchedulerSettingsEnvironment environment = new SchedulerSettingsEnvironment();

    @Test
    void preservesRawDeclarationsExportsStartupFieldsAndProjectsFinalValues() {
        var declared = new LinkedHashMap<String, Object>();
        declared.put("ratePerSec", "${pockethive.inputs.scheduler.tick-interval-ms}");
        declared.put("maxMessages", 0);
        declared.put("reset", true);
        declared.put("maxPendingTicks", null);
        var candidate = environment.candidate(Map.of("type", "SCHEDULER", "scheduler", declared), ignored -> null);

        assertThat(candidate).containsEntry("maxPendingTicks", null).containsEntry("initialDelayMs", 0L)
            .containsEntry("tickIntervalMs", 1000L);
        assertThat(environment.encode(candidate)).containsEntry("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC",
            "${pockethive.inputs.scheduler.tick-interval-ms}").doesNotContainKey("POCKETHIVE_INPUTS_SCHEDULER_RESET")
            .doesNotContainKey("POCKETHIVE_INPUTS_SCHEDULER_MAXPENDINGTICKS");
        assertThatThrownBy(() -> environment.resolve(Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of())),
            candidate, Map.of("pockethive.inputs.scheduler.rate-per-sec", "1000")::get))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("inputs.scheduler.maxPendingTicks");
        assertThat(declared).containsEntry("maxPendingTicks", null);
    }

    @Test
    void resolvesTextAgainstFrozenPropertiesAndKeepsValidatedResetInBootstrap() {
        var candidate = environment.candidate(Map.of("scheduler", Map.of("ratePerSec", "${TICK}", "maxMessages", "${LIMIT}", "reset", true)),
            Map.of("pockethive.inputs.type", "SCHEDULER")::get);
        var finalProperties = Map.of(
            "pockethive.inputs.type", "SCHEDULER",
            "pockethive.inputs.scheduler.rate-per-sec", "1000",
            "pockethive.inputs.scheduler.max-messages", "7",
            "pockethive.inputs.scheduler.initial-delay-ms", "0",
            "pockethive.inputs.scheduler.tick-interval-ms", "1000",
            "pockethive.inputs.scheduler.max-pending-ticks", "1");

        var bootstrap = environment.resolve(Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of())),
            candidate, finalProperties::get);

        assertThat((Map<Object, Object>) ((Map<?, ?>) bootstrap.get("inputs")).get("scheduler"))
            .containsEntry("ratePerSec", 1000.0).containsEntry("maxMessages", 7L)
            .containsEntry("initialDelayMs", 0L).containsEntry("tickIntervalMs", 1000L)
            .containsEntry("maxPendingTicks", 1).containsEntry("reset", true);
    }
}
