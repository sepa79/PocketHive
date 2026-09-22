package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.HistoryPolicy;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: validate supplied runtime settings and parse complete worker configuration candidates.
 * Must not: select service-level overrides, mutate accepted state or apply history to work items.
 * Contract: RESP-WORK-STATE — docs/architecture/runtime-responsibilities.md#resp-work-state.
 */
final class WorkerRuntimeConfiguration {
    static final String HISTORY_POLICY = "historyPolicy";

    private final Map<String, Object> rawConfig;
    private final HistoryPolicy historyPolicy;

    private WorkerRuntimeConfiguration(Map<String, Object> rawConfig, HistoryPolicy historyPolicy) {
        this.rawConfig = Map.copyOf(rawConfig);
        this.historyPolicy = historyPolicy;
    }

    static WorkerRuntimeConfiguration parse(Map<String, Object> rawConfig) {
        Objects.requireNonNull(rawConfig, "rawConfig");
        HistoryPolicy policy = rawConfig.containsKey(HISTORY_POLICY)
            ? parseHistoryPolicy(rawConfig.get(HISTORY_POLICY)) : HistoryPolicy.FULL;
        return new WorkerRuntimeConfiguration(rawConfig, policy);
    }

    static void validatePatch(Map<String, Object> patch) {
        if (patch.containsKey(HISTORY_POLICY)) {
            parseHistoryPolicy(patch.get(HISTORY_POLICY));
        }
    }

    private static HistoryPolicy parseHistoryPolicy(Object value) {
        if (value instanceof String text) {
            try {
                return HistoryPolicy.valueOf(text);
            } catch (IllegalArgumentException exception) {
                throw invalidPolicy();
            }
        }
        throw invalidPolicy();
    }

    Map<String, Object> rawConfig() {
        return rawConfig;
    }

    HistoryPolicy historyPolicy() {
        return historyPolicy;
    }

    private static IllegalArgumentException invalidPolicy() {
        return new IllegalArgumentException(HISTORY_POLICY + " must be one of " + Arrays.toString(HistoryPolicy.values()));
    }
}
