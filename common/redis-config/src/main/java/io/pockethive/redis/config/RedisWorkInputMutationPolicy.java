package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.input.InputRateParser;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: validate Redis dataset live mutations through the neutral Work mutation port.
 * Must not: select Redis inputs, write worker state or access Redis.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class RedisWorkInputMutationPolicy implements WorkInputMutationPolicy {
    private static final String SETTINGS_PATH = "inputs.redis";
    private static final String RATE_PATH = SETTINGS_PATH + ".ratePerSec";
    private static final String LIST_NAME_PATH = SETTINGS_PATH + ".listName";
    private static final WorkMutationDescriptors DESCRIPTORS = new WorkMutationDescriptors(
        java.util.Set.of(RATE_PATH, LIST_NAME_PATH), java.util.Set.of(LIST_NAME_PATH));

    private final RedisConfigurationParser parser;

    public RedisWorkInputMutationPolicy(RedisConfigurationParser parser) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    @Override
    public WorkerInputType type() {
        return WorkerInputType.REDIS_DATASET;
    }

    @Override
    public WorkMutationDescriptors descriptors() {
        return DESCRIPTORS;
    }

    @Override
    public void validate(WorkMutationRequest request) {
        switch (request.fieldPath()) {
            case RATE_PATH -> validateRate(request);
            case LIST_NAME_PATH -> validateListName(request);
            default -> throw new IllegalArgumentException("Unsupported Redis mutable field '" + request.fieldPath() + "'");
        }
    }

    private void validateRate(WorkMutationRequest request) {
        var result = new InputRateParser().validate(request.updatedValue(), RATE_PATH, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) throw invalid(request, result.problems().getFirst().message());
    }

    private void validateListName(WorkMutationRequest request) {
        var requested = parser.validateRedisDatasetSelection(request.updatedValue(), List.of(), SETTINGS_PATH,
            WorkConfigurationMode.RESOLVED);
        if (!requested.problems().isEmpty()) throw invalid(request, requested.problems().getFirst().message());
        if (!requested.listName().equals(request.updatedValue())) throw invalid(request, "must not contain surrounding whitespace");
        Map<String, Object> previous = request.previousSettings();
        Object sources = previous.containsKey("sources") ? previous.get("sources") : List.of();
        var prior = parser.validateRedisDatasetSelection(previous.get("listName"), sources, SETTINGS_PATH,
            WorkConfigurationMode.RESOLVED);
        if (prior.mode() != RedisDatasetSourceMode.SINGLE) {
            throw new IllegalStateException("Runtime config-update cannot change disabled-only IO field '" + LIST_NAME_PATH
                + "' for worker '" + request.workerName() + "'; the worker must already use Redis single-source listName mode.");
        }
    }

    private static IllegalArgumentException invalid(WorkMutationRequest request, String reason) {
        return new IllegalArgumentException("Runtime config-update has invalid operational IO field '" + request.fieldPath()
            + "' for worker '" + request.workerName() + "': " + reason + ".");
    }
}
