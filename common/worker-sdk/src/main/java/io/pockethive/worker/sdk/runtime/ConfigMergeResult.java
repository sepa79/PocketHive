package io.pockethive.worker.sdk.runtime;

import java.util.Map;

/**
 * Responsibility: carry one merged configuration candidate and its change description.
 * Must not: accept state, parse settings or decide control command success.
 * Contract: RESP-WORK-STATE — docs/architecture/runtime-responsibilities.md#resp-work-state.
 */
record ConfigMergeResult(
    Map<String, Object> previousRaw,
    WorkerRuntimeConfiguration configuration,
    Object typedConfig,
    boolean replaced,
    Map<String, Object> diff
) {
    Map<String, Object> rawConfig() {
        return configuration.rawConfig();
    }
}
