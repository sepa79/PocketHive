package io.pockethive.work.local.scheduler;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkInputSettingsParseResult;
import io.pockethive.work.config.WorkInputSettingsParser;
import io.pockethive.work.config.WorkerInputType;
import java.util.Map;

/**
 * Responsibility: adapt canonical scheduler settings parsing to the neutral input parser port.
 * Must not: select inputs, retain accepted state or schedule work.
 * Contract: RESP-WORK-SCHEDULER-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-settings.
 */
public final class SchedulerWorkInputSettingsParser implements WorkInputSettingsParser {
    private final SchedulerSettingsParser parser;

    public SchedulerWorkInputSettingsParser(SchedulerSettingsParser parser) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    @Override
    public WorkerInputType type() { return WorkerInputType.SCHEDULER; }

    @Override
    public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        var result = parser.validate(settings, path, mode);
        return new WorkInputSettingsParseResult(result.settings(), result.problems(), result.deferredPaths());
    }
}
