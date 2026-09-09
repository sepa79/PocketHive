package io.pockethive.work.config;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: own IO patch mutability and validate changes against accepted settings and enablement.
 * Must not: write accepted state, apply adapters, read environment or replace complete candidate validation.
 * Consumes: RESP-WORK-REDIS-SELECTION for the requested list name and prior source mode.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class WorkPatchPolicy {

    private static final String INPUTS_ROOT = "inputs";
    private static final String OUTPUTS_ROOT = "outputs";
    private static final String TYPE_FIELD = "type";
    private static final double MIN_RATE_PER_SEC = 0.0;

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

    private final String workerName;
    private final WorkerInputType inputType;
    private final WorkerOutputType outputType;

    public WorkPatchPolicy(String workerName, WorkerInputType inputType, WorkerOutputType outputType) {
        this.workerName = Objects.requireNonNull(workerName, "workerName");
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
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

    public void validate(
        Map<String, Object> previousRaw,
        Map<String, Object> update,
        boolean workerEnabled
    ) {
        Objects.requireNonNull(update, "update");
        if (update.isEmpty()) {
            return;
        }
        Map<String, Object> previous = Objects.requireNonNull(previousRaw, "previousRaw");
        boolean bootstrap = previous.isEmpty();
        validateIoRoot(
            INPUTS_ROOT,
            inputType.settingsKey(),
            previous,
            update,
            bootstrap,
            workerEnabled
        );
        validateIoRoot(
            OUTPUTS_ROOT,
            outputType.settingsKey(),
            previous,
            update,
            bootstrap,
            workerEnabled
        );
    }

    public void validateReset(Map<String, Object> previousRaw) {
        Objects.requireNonNull(previousRaw, "previousRaw");
        if (previousRaw.isEmpty()) {
            return;
        }
        if (previousRaw.containsKey(INPUTS_ROOT)) {
            throw unsafeUpdate(INPUTS_ROOT);
        }
        if (previousRaw.containsKey(OUTPUTS_ROOT)) {
            throw unsafeUpdate(OUTPUTS_ROOT);
        }
    }

    private void validateIoRoot(
        String root,
        String selectedSubblock,
        Map<String, Object> previousRaw,
        Map<String, Object> update,
        boolean bootstrap,
        boolean workerEnabled
    ) {
        Object rawRootUpdate = update.get(root);
        if (rawRootUpdate == null) {
            return;
        }
        if (!(rawRootUpdate instanceof Map<?, ?> rootUpdate)) {
            throw unsafeUpdate(root);
        }
        for (Map.Entry<?, ?> entry : rootUpdate.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            Object value = entry.getValue();
            String path = root + "." + key;
            if (TYPE_FIELD.equals(key)) {
                if (!bootstrap) {
                    rejectIfChanged(previousRaw, path, value);
                }
                continue;
            }
            if (!(value instanceof Map<?, ?> nestedUpdate)) {
                throw unsafeUpdate(path);
            }
            validateSubblock(selectedSubblock, previousRaw, path, nestedUpdate, bootstrap, workerEnabled);
        }
    }

    private void validateSubblock(
        String selectedSubblock,
        Map<String, Object> previousRaw,
        String subblockPath,
        Map<?, ?> nestedUpdate,
        boolean bootstrap,
        boolean workerEnabled
    ) {
        for (Map.Entry<?, ?> nestedEntry : nestedUpdate.entrySet()) {
            if (nestedEntry.getKey() == null) {
                continue;
            }
            String field = nestedEntry.getKey().toString();
            String fieldPath = subblockPath + "." + field;
            boolean safe = subblockPath.endsWith("." + selectedSubblock)
                && isLiveMutableIoPath(fieldPath);
            if (safe) {
                validateSafeOperationalField(
                    previousRaw,
                    fieldPath,
                    nestedEntry.getValue(),
                    workerEnabled
                );
                continue;
            }
            if (bootstrap) {
                continue;
            }
            rejectIfChanged(previousRaw, fieldPath, nestedEntry.getValue());
        }
    }

    private void validateSafeOperationalField(
        Map<String, Object> previousRaw,
        String dottedPath,
        Object value,
        boolean workerEnabled
    ) {
        if (isDisabledOnlyIoPath(dottedPath)) {
            validateDisabledRedisListName(previousRaw, dottedPath, value, workerEnabled);
            return;
        }
        switch (dottedPath) {
            case SCHEDULER_RATE_PER_SEC,
                 REDIS_DATASET_RATE_PER_SEC,
                 CSV_DATASET_RATE_PER_SEC ->
                requireRatePerSec(dottedPath, value);
            case SCHEDULER_MAX_MESSAGES -> requireNonNegativeInteger(dottedPath, value);
            case SCHEDULER_RESET -> requireBoolean(dottedPath, value);
            default -> throw unsafeUpdate(dottedPath);
        }
    }

    private void validateDisabledRedisListName(
        Map<String, Object> previousRaw,
        String dottedPath,
        Object value,
        boolean workerEnabled
    ) {
        if (previousRaw.isEmpty()) {
            return;
        }
        Object previousValue = valueAt(previousRaw, dottedPath);
        if (Objects.equals(previousValue, value)) {
            return;
        }
        if (workerEnabled) {
            throw new IllegalStateException(
                "Runtime config-update cannot change disabled-only IO field '" + dottedPath
                    + "' for enabled worker '" + workerName + "'; stop the swarm first."
            );
        }
        var parser = new WorkConfigurationParser();
        String sourcePath = INPUTS_ROOT + "." + inputType.settingsKey();
        var requestedSelection = parser.validateRedisDatasetSelection(value, java.util.List.of(), sourcePath,
            WorkConfigurationMode.RESOLVED);
        if (!requestedSelection.problems().isEmpty()) {
            throw invalidOperationalValue(dottedPath, requestedSelection.problems().getFirst().message());
        }
        if (!requestedSelection.listName().equals(value)) {
            throw invalidOperationalValue(dottedPath, "must not contain surrounding whitespace");
        }
        Object redis = valueAt(previousRaw, sourcePath);
        Map<?, ?> previousSettings = redis instanceof Map<?, ?> fields ? fields : Map.of();
        var previousSelection = parser.validateRedisDatasetSelection(previousSettings.get("listName"),
            previousSettings.containsKey("sources") ? previousSettings.get("sources") : java.util.List.of(),
            sourcePath, WorkConfigurationMode.RESOLVED);
        if (previousSelection.mode() != RedisDatasetSourceMode.SINGLE) {
            throw new IllegalStateException(
                "Runtime config-update cannot change disabled-only IO field '" + dottedPath
                    + "' for worker '" + workerName
                    + "'; the worker must already use Redis single-source listName mode."
            );
        }
    }

    private double requireRatePerSec(String dottedPath, Object value) {
        double rate = requireNumber(dottedPath, value);
        if (rate < MIN_RATE_PER_SEC) {
            throw invalidOperationalValue(
                dottedPath,
                "must be >= " + formatNumber(MIN_RATE_PER_SEC)
            );
        }
        return rate;
    }

    private long requireNonNegativeInteger(String dottedPath, Object value) {
        if (!(value instanceof Number number)) {
            throw invalidOperationalValue(dottedPath, "must be an integer");
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
            throw invalidOperationalValue(dottedPath, "must be an integer");
        }
        long integer = number.longValue();
        if (integer < 0L) {
            throw invalidOperationalValue(dottedPath, "must be >= 0");
        }
        return integer;
    }

    private double requireNumber(String dottedPath, Object value) {
        if (!(value instanceof Number number)) {
            throw invalidOperationalValue(dottedPath, "must be a number");
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric)) {
            throw invalidOperationalValue(dottedPath, "must be a finite number");
        }
        return numeric;
    }

    private boolean requireBoolean(String dottedPath, Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalidOperationalValue(dottedPath, "must be true or false");
    }

    private void rejectIfChanged(
        Map<String, Object> previousRaw,
        String dottedPath,
        Object updatedValue
    ) {
        Object previousValue = valueAt(previousRaw, dottedPath);
        if (!Objects.equals(previousValue, updatedValue)) {
            throw unsafeUpdate(dottedPath);
        }
    }

    private Object valueAt(Map<String, Object> source, String dottedPath) {
        Object current = source;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private IllegalStateException unsafeUpdate(String dottedPath) {
        return new IllegalStateException(
            "Runtime config-update cannot change unsafe IO field '" + dottedPath
                + "' for worker '" + workerName
                + "'; restart the worker/swarm to change input or output wiring."
        );
    }

    private IllegalArgumentException invalidOperationalValue(
        String dottedPath,
        String reason
    ) {
        return new IllegalArgumentException(
            "Runtime config-update has invalid operational IO field '" + dottedPath
                + "' for worker '" + workerName + "': " + reason + "."
        );
    }

    private String formatNumber(double value) {
        if (Double.isFinite(value) && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
