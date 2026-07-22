package io.pockethive.scenarios.validation;

import java.util.Set;

public final class LiveIoConfigMutability {

    public static final String LIVE_MUTABLE_FIELD = "liveMutable";
    public static final String SCHEDULER_RATE_PER_SEC = "inputs.scheduler.ratePerSec";
    public static final String SCHEDULER_MAX_MESSAGES = "inputs.scheduler.maxMessages";
    public static final String SCHEDULER_RESET = "inputs.scheduler.reset";
    public static final String REDIS_DATASET_RATE_PER_SEC = "inputs.redis.ratePerSec";
    public static final String REDIS_DATASET_LIST_NAME = "inputs.redis.listName";
    public static final String REDIS_DATASET_SOURCES = "inputs.redis.sources";
    public static final String CSV_DATASET_RATE_PER_SEC = "inputs.csv.ratePerSec";

    private static final String INPUTS_PREFIX = "inputs.";
    private static final String OUTPUTS_PREFIX = "outputs.";
    private static final Set<String> LIVE_MUTABLE_IO_PATHS = Set.of(
            SCHEDULER_RATE_PER_SEC,
            SCHEDULER_MAX_MESSAGES,
            SCHEDULER_RESET,
            REDIS_DATASET_RATE_PER_SEC,
            REDIS_DATASET_LIST_NAME,
            CSV_DATASET_RATE_PER_SEC
    );
    private static final Set<String> DISABLED_ONLY_IO_PATHS = Set.of(REDIS_DATASET_LIST_NAME);

    private LiveIoConfigMutability() {
    }

    public static boolean isIoPath(String path) {
        return path != null && (path.startsWith(INPUTS_PREFIX) || path.startsWith(OUTPUTS_PREFIX));
    }

    public static boolean isLiveMutableIoPath(String path) {
        return LIVE_MUTABLE_IO_PATHS.contains(path);
    }

    public static Set<String> liveMutableIoPaths() {
        return LIVE_MUTABLE_IO_PATHS;
    }

    public static boolean isDisabledOnlyIoPath(String path) {
        return DISABLED_ONLY_IO_PATHS.contains(path);
    }

    public static Set<String> disabledOnlyIoPaths() {
        return DISABLED_ONLY_IO_PATHS;
    }
}
