package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.scenarios.validation.LiveIoConfigMutability;
import java.util.Map;
import java.util.Objects;

final class LiveIoConfigUpdateGuard {

    private static final String INPUTS_ROOT = "inputs";
    private static final String OUTPUTS_ROOT = "outputs";
    private static final String TYPE_FIELD = "type";
    private static final double MIN_RATE_PER_SEC = 0.0;

    private LiveIoConfigUpdateGuard() {
    }

    static void validate(
        WorkerDefinition definition,
        Map<String, Object> previousRaw,
        Map<String, Object> update,
        boolean workerEnabled
    ) {
        if (definition == null || update == null || update.isEmpty()) {
            return;
        }
        Map<String, Object> previous = previousRaw == null ? Map.of() : previousRaw;
        boolean bootstrap = previous.isEmpty();
        validateIoRoot(
            definition,
            INPUTS_ROOT,
            inputSubblock(definition.input()),
            previous,
            update,
            bootstrap,
            workerEnabled
        );
        validateIoRoot(
            definition,
            OUTPUTS_ROOT,
            outputSubblock(definition.outputType()),
            previous,
            update,
            bootstrap,
            workerEnabled
        );
    }

    static void validateReset(WorkerDefinition definition, Map<String, Object> previousRaw) {
        if (definition == null || previousRaw == null || previousRaw.isEmpty()) {
            return;
        }
        if (previousRaw.containsKey(INPUTS_ROOT)) {
            throw unsafeUpdate(definition, INPUTS_ROOT);
        }
        if (previousRaw.containsKey(OUTPUTS_ROOT)) {
            throw unsafeUpdate(definition, OUTPUTS_ROOT);
        }
    }

    private static void validateIoRoot(
        WorkerDefinition definition,
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
            throw unsafeUpdate(definition, root);
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
                    rejectIfChanged(definition, previousRaw, path, value);
                }
                continue;
            }
            if (!(value instanceof Map<?, ?> nestedUpdate)) {
                throw unsafeUpdate(definition, path);
            }
            validateSubblock(definition, selectedSubblock, previousRaw, path, nestedUpdate, bootstrap, workerEnabled);
        }
    }

    private static void validateSubblock(
        WorkerDefinition definition,
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
            boolean safe = selectedSubblock != null
                && subblockPath.endsWith("." + selectedSubblock)
                && LiveIoConfigMutability.isLiveMutableIoPath(fieldPath);
            if (safe) {
                validateSafeOperationalField(
                    definition,
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
            rejectIfChanged(definition, previousRaw, fieldPath, nestedEntry.getValue());
        }
    }

    private static void validateSafeOperationalField(
        WorkerDefinition definition,
        Map<String, Object> previousRaw,
        String dottedPath,
        Object value,
        boolean workerEnabled
    ) {
        if (LiveIoConfigMutability.isDisabledOnlyIoPath(dottedPath)) {
            validateDisabledRedisListName(definition, previousRaw, dottedPath, value, workerEnabled);
            return;
        }
        switch (dottedPath) {
            case LiveIoConfigMutability.SCHEDULER_RATE_PER_SEC,
                 LiveIoConfigMutability.REDIS_DATASET_RATE_PER_SEC,
                 LiveIoConfigMutability.CSV_DATASET_RATE_PER_SEC ->
                requireRatePerSec(definition, dottedPath, value);
            case LiveIoConfigMutability.SCHEDULER_MAX_MESSAGES -> requireNonNegativeInteger(definition, dottedPath, value);
            case LiveIoConfigMutability.SCHEDULER_RESET -> requireBoolean(definition, dottedPath, value);
            default -> throw unsafeUpdate(definition, dottedPath);
        }
    }

    private static void validateDisabledRedisListName(
        WorkerDefinition definition,
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
                    + "' for enabled worker '" + definition.beanName() + "'; stop the swarm first."
            );
        }
        if (!(value instanceof String listName) || listName.isBlank()) {
            throw invalidOperationalValue(definition, dottedPath, "must be a non-blank string");
        }
        if (!listName.equals(listName.trim())) {
            throw invalidOperationalValue(definition, dottedPath, "must not contain surrounding whitespace");
        }
        Object previousListName = valueAt(previousRaw, LiveIoConfigMutability.REDIS_DATASET_LIST_NAME);
        Object previousSources = valueAt(previousRaw, LiveIoConfigMutability.REDIS_DATASET_SOURCES);
        if (!(previousListName instanceof String currentListName) || currentListName.isBlank()
            || hasConfiguredSources(previousSources)) {
            throw new IllegalStateException(
                "Runtime config-update cannot change disabled-only IO field '" + dottedPath
                    + "' for worker '" + definition.beanName()
                    + "'; the worker must already use Redis single-source listName mode."
            );
        }
    }

    private static boolean hasConfiguredSources(Object value) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Iterable<?> sources)) {
            return true;
        }
        return sources.iterator().hasNext();
    }

    private static double requireRatePerSec(WorkerDefinition definition, String dottedPath, Object value) {
        double rate = requireNumber(definition, dottedPath, value);
        if (rate < MIN_RATE_PER_SEC) {
            throw invalidOperationalValue(
                definition,
                dottedPath,
                "must be >= " + formatNumber(MIN_RATE_PER_SEC)
            );
        }
        return rate;
    }

    private static long requireNonNegativeInteger(WorkerDefinition definition, String dottedPath, Object value) {
        if (!(value instanceof Number number)) {
            throw invalidOperationalValue(definition, dottedPath, "must be an integer");
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)) {
            throw invalidOperationalValue(definition, dottedPath, "must be an integer");
        }
        long integer = number.longValue();
        if (integer < 0L) {
            throw invalidOperationalValue(definition, dottedPath, "must be >= 0");
        }
        return integer;
    }

    private static double requireNumber(WorkerDefinition definition, String dottedPath, Object value) {
        if (!(value instanceof Number number)) {
            throw invalidOperationalValue(definition, dottedPath, "must be a number");
        }
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric)) {
            throw invalidOperationalValue(definition, dottedPath, "must be a finite number");
        }
        return numeric;
    }

    private static boolean requireBoolean(WorkerDefinition definition, String dottedPath, Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalidOperationalValue(definition, dottedPath, "must be true or false");
    }

    private static void rejectIfChanged(
        WorkerDefinition definition,
        Map<String, Object> previousRaw,
        String dottedPath,
        Object updatedValue
    ) {
        Object previousValue = valueAt(previousRaw, dottedPath);
        if (!Objects.equals(previousValue, updatedValue)) {
            throw unsafeUpdate(definition, dottedPath);
        }
    }

    private static Object valueAt(Map<String, Object> source, String dottedPath) {
        Object current = source;
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static String inputSubblock(WorkerInputType inputType) {
        if (inputType == WorkerInputType.SCHEDULER) {
            return "scheduler";
        }
        if (inputType == WorkerInputType.REDIS_DATASET) {
            return "redis";
        }
        if (inputType == WorkerInputType.CSV_DATASET) {
            return "csv";
        }
        if (inputType == WorkerInputType.RABBITMQ) {
            return "rabbit";
        }
        return null;
    }

    private static String outputSubblock(WorkerOutputType outputType) {
        if (outputType == WorkerOutputType.REDIS) {
            return "redis";
        }
        if (outputType == WorkerOutputType.RABBITMQ) {
            return "rabbit";
        }
        return null;
    }

    private static IllegalStateException unsafeUpdate(WorkerDefinition definition, String dottedPath) {
        return new IllegalStateException(
            "Runtime config-update cannot change unsafe IO field '" + dottedPath
                + "' for worker '" + definition.beanName()
                + "'; restart the worker/swarm to change input or output wiring."
        );
    }

    private static IllegalArgumentException invalidOperationalValue(
        WorkerDefinition definition,
        String dottedPath,
        String reason
    ) {
        return new IllegalArgumentException(
            "Runtime config-update has invalid operational IO field '" + dottedPath
                + "' for worker '" + definition.beanName() + "': " + reason + "."
        );
    }

    private static String formatNumber(double value) {
        if (Double.isFinite(value) && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
