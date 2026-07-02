package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class LiveIoConfigUpdateGuard {

    private static final String INPUTS_ROOT = "inputs";
    private static final String OUTPUTS_ROOT = "outputs";
    private static final String TYPE_FIELD = "type";
    private static final String SCHEDULER_RATE_PER_SEC = "inputs.scheduler.ratePerSec";
    private static final String SCHEDULER_MAX_MESSAGES = "inputs.scheduler.maxMessages";
    private static final String SCHEDULER_RESET = "inputs.scheduler.reset";
    private static final String REDIS_DATASET_RATE_PER_SEC = "inputs.redis.ratePerSec";
    private static final String CSV_DATASET_RATE_PER_SEC = "inputs.csv.ratePerSec";
    private static final double MIN_RATE_PER_SEC = 0.0;

    private LiveIoConfigUpdateGuard() {
    }

    static void validate(WorkerDefinition definition, Map<String, Object> previousRaw, Map<String, Object> update) {
        if (definition == null || update == null || update.isEmpty()) {
            return;
        }
        Map<String, Object> previous = previousRaw == null ? Map.of() : previousRaw;
        boolean bootstrap = previous.isEmpty();
        validateIoRoot(
            definition,
            INPUTS_ROOT,
            inputSubblock(definition.input()),
            safeInputFields(definition.input()),
            previous,
            update,
            bootstrap
        );
        validateIoRoot(
            definition,
            OUTPUTS_ROOT,
            outputSubblock(definition.outputType()),
            Set.of(),
            previous,
            update,
            bootstrap
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
        Set<String> safeFields,
        Map<String, Object> previousRaw,
        Map<String, Object> update,
        boolean bootstrap
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
            validateSubblock(definition, selectedSubblock, safeFields, previousRaw, path, nestedUpdate, bootstrap);
        }
    }

    private static void validateSubblock(
        WorkerDefinition definition,
        String selectedSubblock,
        Set<String> safeFields,
        Map<String, Object> previousRaw,
        String subblockPath,
        Map<?, ?> nestedUpdate,
        boolean bootstrap
    ) {
        for (Map.Entry<?, ?> nestedEntry : nestedUpdate.entrySet()) {
            if (nestedEntry.getKey() == null) {
                continue;
            }
            String field = nestedEntry.getKey().toString();
            String fieldPath = subblockPath + "." + field;
            boolean safe = selectedSubblock != null
                && subblockPath.endsWith("." + selectedSubblock)
                && safeFields.contains(field);
            if (safe) {
                validateSafeOperationalField(definition, fieldPath, nestedEntry.getValue());
                continue;
            }
            if (bootstrap) {
                continue;
            }
            rejectIfChanged(definition, previousRaw, fieldPath, nestedEntry.getValue());
        }
    }

    private static void validateSafeOperationalField(WorkerDefinition definition, String dottedPath, Object value) {
        switch (dottedPath) {
            case SCHEDULER_RATE_PER_SEC, REDIS_DATASET_RATE_PER_SEC, CSV_DATASET_RATE_PER_SEC ->
                requireRatePerSec(definition, dottedPath, value);
            case SCHEDULER_MAX_MESSAGES -> requireNonNegativeInteger(definition, dottedPath, value);
            case SCHEDULER_RESET -> requireBoolean(definition, dottedPath, value);
            default -> throw unsafeUpdate(definition, dottedPath);
        }
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

    private static Set<String> safeInputFields(WorkerInputType inputType) {
        if (inputType == WorkerInputType.SCHEDULER) {
            return Set.of("ratePerSec", "maxMessages", "reset");
        }
        if (inputType == WorkerInputType.REDIS_DATASET || inputType == WorkerInputType.CSV_DATASET) {
            return Set.of("ratePerSec");
        }
        return Set.of();
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
