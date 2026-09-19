package io.pockethive.worker.sdk.testing;

import io.pockethive.work.config.*;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project canonical memory settings validation to the input parser port.
 * Must not: select another type or create resources.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class InMemoryWorkInputParser implements WorkInputSettingsParser {
    @Override public WorkIoType type() { return InMemoryWorkType.MEMORY; }
    @Override public WorkInputSettingsParseResult validate(Map<?, ?> fields, String path, WorkConfigurationMode mode) {
        try {
            return new WorkInputSettingsParseResult(new InMemoryWorkInputSettings(InMemoryWorkAddress.parse(fields)), List.of(), List.of());
        } catch (IllegalArgumentException | NullPointerException failure) {
            return new WorkInputSettingsParseResult(null, List.of(new WorkConfigurationProblem(path, failure.getMessage())), List.of());
        }
    }
}
